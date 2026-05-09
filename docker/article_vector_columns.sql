ALTER TABLE article
    ADD COLUMN vector_id VARCHAR(100) DEFAULT NULL COMMENT '向量库ID',
    ADD COLUMN vector_status TINYINT DEFAULT 0 COMMENT '向量同步状态：0未同步，1同步成功，2同步失败',
    ADD COLUMN vector_error VARCHAR(500) DEFAULT NULL COMMENT '向量同步失败原因';
