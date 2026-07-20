package com.xspaceagi.knowledge.core.application.service.impl;

import com.xspaceagi.knowledge.core.application.service.IKnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextSyncService;
import com.xspaceagi.knowledge.core.application.vo.KnowledgeConfigApplicationRequestVo;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeQaSegmentRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeConfigDomainService;
import com.xspaceagi.knowledge.domain.service.impl.KnowledgeQaSegmentDomainService;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.page.PageQueryParamVo;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.infra.service.QueryVoListDelegateService;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class KnowledgeConfigApplicationService implements IKnowledgeConfigApplicationService {

    @Resource
    private IKnowledgeConfigDomainService knowledgeConfigDomainService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;
    @Resource
    private QueryVoListDelegateService queryVoListDelegateService;

    @Resource
    private IKnowledgeFullTextSyncService fullTextSyncService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private KnowledgeQaSegmentDomainService knowledgeQaSegmentDomainService;

    @Resource
    private ThreadTenantUtil threadTenantUtil;

    @Resource
    private VectorDBService vectorDBService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private IKnowledgeQaSegmentRepository knowledgeQaSegmentRepository;

    @Override
    public SuperPage<KnowledgeConfigModel> querySearchConfigs(
            PageQueryVo<KnowledgeConfigApplicationRequestVo> pageQueryVo) {

        var filter = pageQueryVo.getQueryFilter();
        pageQueryVo.setQueryFilter(filter);

        PageQueryParamVo pageQueryParamVo = new PageQueryParamVo(pageQueryVo);

        SuperPage<KnowledgeConfigModel> superPage = this.queryVoListDelegateService.queryVoList(
                this.knowledgeConfigRepository,
                pageQueryParamVo, null);

        return superPage;
    }

    @Override
    public KnowledgeConfigModel queryOneInfoById(Long id) {
        return this.knowledgeConfigDomainService.queryOneInfoById(id);
    }

    @Override
    public void deleteById(Long id, UserContext userContext) {
        var existObj = this.knowledgeConfigDomainService.queryOneInfoById(id);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 删除知识库（包括数据库、向量库、全文检索，在 Domain 层事务内处理）
        this.knowledgeConfigDomainService.deleteById(id, userContext);
    }

    @Override
    public Long updateInfo(KnowledgeConfigModel model, UserContext userContext) {

        var existObj = this.knowledgeConfigDomainService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 向量模型发生变化（id 不同，含“同维度不同厂商”）时，必须重建向量集合与重向量化：
        // 不同模型的向量处于不同向量空间，即使维度相同也不能混用（写入/查询共用同一模型，旧向量不可复用）。
        if (existObj.getEmbeddingModelId() != null
                && !existObj.getEmbeddingModelId().equals(model.getEmbeddingModelId())) {

            Integer oldDim = queryModelDimension(existObj.getEmbeddingModelId());
            Integer newDim = queryModelDimension(model.getEmbeddingModelId());
            log.info("KB [{}] embedding model changed ({} dim {} -> {} dim {}), rebuild collection",
                    model.getId(), existObj.getEmbeddingModelId(), oldDim,
                    model.getEmbeddingModelId(), newDim);

            // 先标记该知识库下的问答为待向量化
            String updateSql = " update knowledge_qa_segment set has_embedding = ?, created = now() where kb_id = ? ";
            jdbcTemplate.update(updateSql, new Object[] { 0, model.getId() });

            // 删除旧集合，并【同步】按新模型维度重建空集合，避免异步重向量化与查询并发时
            // 出现“新维度向量写入旧维度集合”的维度冲突（2048 != 1536）
            vectorDBService.deleteCollection(model.getId());
            vectorDBService.initAndCheckCollection(model.getId(), model.getEmbeddingModelId());

            // 查询该知识库下所有问答，异步用新模型重新向量化
            String querySql = " select id from knowledge_qa_segment where kb_id = ? ";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(querySql,
                    new Object[] { model.getId() });
            List<Long> ids = new ArrayList<>();
            if (list != null) {
                for (Map<String, Object> map : list) {
                    ids.add((Long) map.get("id"));
                }
            }
            List<KnowledgeQaSegmentModel> modelList = knowledgeQaSegmentRepository.queryListByIds(ids);
            var runnable = new TenantRunnable(() -> {
                knowledgeQaSegmentDomainService.batchAddEmbeddingQa(modelList, userContext);
            });
            threadTenantUtil.obtainOtherScheduledExecutor().execute(runnable);
        }

        return this.knowledgeConfigDomainService.updateInfo(model, userContext);
    }

    /**
     * 查询向量模型维度，查询失败返回 null
     */
    private Integer queryModelDimension(Long modelId) {
        if (modelId == null) {
            return null;
        }
        try {
            var modelConfig = modelApplicationService.queryModelConfigById(modelId);
            return modelConfig != null ? modelConfig.getDimension() : null;
        } catch (Exception e) {
            log.warn("Query embedding model dimension failed, modelId={}", modelId, e);
            return null;
        }
    }

    @Override
    public Long addInfo(KnowledgeConfigModel model, UserContext userContext) {
        var spaceId = model.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        return this.knowledgeConfigDomainService.addInfo(model, userContext);
    }

    @Override
    public List<KnowledgeConfigModel> queryListBySpaceId(Long spaceId) {
        return knowledgeConfigDomainService.queryListBySpaceId(spaceId);
    }

    @Override
    public Long queryTotalFileSize(Long kbId) {
        return this.knowledgeConfigDomainService.queryTotalFileSize(kbId);
    }

}
