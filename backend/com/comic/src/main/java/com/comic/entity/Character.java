package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_character")
public class Character {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String charId;
    private String name;
    private String role;
    private String profileJson;
    private String currentStateJson;

    // 鏂板瀛楁锛堢敤浜庤鑹叉彁鍙栧拰褰㈣薄绠＄悊锛?
    private String projectId;         // 椤圭洰ID
    private String personality;       // 鎬ф牸鎻忚堪
    private String appearance;        // 澶栬矊鎻忚堪
    private String background;        // 鑳屾櫙鏁呬簨
    private Boolean confirmed;        // 鏄惁宸茬‘璁?
    private Boolean locked;           // 鏄惁宸查攣瀹氳祫浜?

    // 瑙掕壊鍥剧墖鐢熸垚鐩稿叧瀛楁
    // 鐢熸垚鐘舵€佸瓧娈?
    private String expressionStatus;    // 琛ㄦ儏鐢熸垚鐘舵€? pending/generating/completed/failed
    private String threeViewStatus;     // 涓夎鍥剧敓鎴愮姸鎬? pending/generating/completed/failed

    // 鐢熸垚鍐呭瀛樺偍锛圝SON鏍煎紡锛?
    private String expressionPrompt;    // 琛ㄦ儏鐢熸垚鎻愮ず璇?
    private String threeViewPrompt;     // 涓夎鍥剧敓鎴愭彁绀鸿瘝

    // 閿欒淇℃伅
    private String expressionError;     // 琛ㄦ儏鐢熸垚閿欒淇℃伅
    private String threeViewError;      // 涓夎鍥剧敓鎴愰敊璇俊鎭?

    // 鐢熸垚鏍囧織
    private Boolean isGeneratingExpression;   // 姝ｅ湪鐢熸垚琛ㄦ儏
    private Boolean isGeneratingThreeView;    // 姝ｅ湪鐢熸垚涓夎鍥?

    // 瑙嗚椋庢牸
    private String visualStyle;          // 3D/REAL/ANIME锛岄粯璁?D

    // 澶у叏鍥綰RL
    private String expressionGridUrl;    // 涔濆鏍煎ぇ鍏ㄥ浘URL
    private String threeViewGridUrl;     // 涓夎鍥惧ぇ鍏ㄥ浘URL
    private String expressionGridPrompt; // 涔濆鏍兼彁绀鸿瘝璁板綍
    private String threeViewGridPrompt;  // 涓夎鍥炬彁绀鸿瘝璁板綍

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

