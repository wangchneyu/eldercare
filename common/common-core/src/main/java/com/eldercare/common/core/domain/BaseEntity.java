package com.eldercare.common.core.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class BaseEntity implements Serializable {
    // 关键修正：将 Long 序列化为 String 交给前端
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}