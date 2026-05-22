package com.yupi.yuaiagent.archived.config;

import lombok.extern.slf4j.Slf4j;

/**
 * 向量库清理组件（已禁用自动清理）
 * 如需重置向量库，手动在 Docker 中删除集合后重启即可。
 * initialize-schema: true 会自动重建。
 */
@Slf4j
// @Component - 禁用，避免启动时序问题
public class VectorStoreCleanupConfig {

}
