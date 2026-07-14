package com.eldercare.common.feign.dto.family;

import lombok.Data;
import java.io.Serializable;

@Data
public class FamilyMemberRemoteDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long elderId;
    private String name;
    private String relationship;
    private String phone;
}
