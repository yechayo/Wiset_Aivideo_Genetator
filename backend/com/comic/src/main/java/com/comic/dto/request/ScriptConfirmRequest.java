package com.comic.dto.request;

import lombok.Data;

/**
 * 剧本确认/修改请求DTO
 */
@Data
public class ScriptConfirmRequest {
    private Boolean confirmed;        // true=确认，false=要求修改
    private String revisionNote;      // 修改意见（confirmed=false时必填）
}
