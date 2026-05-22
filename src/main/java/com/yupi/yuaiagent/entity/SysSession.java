package com.yupi.yuaiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_session")
public class SysSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("token")
    private String token;
    @TableField("user_id")
    private String userId;
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField("expire_time")
    private LocalDateTime expireTime;
}
