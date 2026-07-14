package com.eldercare.common.security.context;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class UserInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private List<String> roles;
}
