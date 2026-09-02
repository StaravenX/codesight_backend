package com.codesight.profile.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创作者名片静态资料数据传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorCardStatic implements Serializable {

    private Long id;
    private String nickname;
    private String avatar;
    private String bio;
    private String jobTitle;
    private String company;
}
