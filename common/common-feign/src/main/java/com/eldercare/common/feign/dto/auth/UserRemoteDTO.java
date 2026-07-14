package com.eldercare.common.feign.dto.auth;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class UserRemoteDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String realName;
    private List<String> roles;
}
