package com.xspaceagi.custompage.domain.repository;

import java.util.List;

import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.page.SuperPage;

/**
 * 自定义页面会话记录仓储接口
 */
public interface ICustomPageConversationRepository {

    /**
     * 保存会话记录
     */
    Long save(CustomPageConversationModel model, UserContext userContext);

    /**
     * 根据项目ID查询会话记录列表
     */
    List<CustomPageConversationModel> listByProjectId(Long projectId, Long userId);

    /**
     * 分页查询会话记录
     */
    SuperPage<CustomPageConversationModel> pageQuery(CustomPageConversationModel queryModel, Long current,
            Long pageSize);

    /**
     * 根据requestId回填用户消息的sessionId
     */
    boolean updateUserSessionIdByRequestId(Long projectId, String requestId, String sessionId, Long userId);

    /**
     * 按项目ID删除会话记录（软删）
     */
    boolean deleteByProjectId(Long projectId, Long userId);

}
