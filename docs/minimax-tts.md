> ## Documentation Index
>
> Fetch the complete documentation index at: https://platform.minimaxi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 同步语音合成 HTTP

> 使用本接口，在HTTP网络通信协议下进行同步语音合成。

备用接口地址

`https://api-bj.minimaxi.com/v1/t2a_v2`

## OpenAPI

````yaml
openapi: 3.1.0
info:
  title: MiniMax T2A API
  description: >-
    MiniMax Text-to-Audio API with support for streaming and non-streaming
    output
  license:
    name: MIT
  version: 1.0.0
servers:
  - url: https://api.minimaxi.com
security:
  - bearerAuth: []
paths:
  /v1/t2a_v2:
    post:
      tags:
        - Text to Audio
      summary: Text to Audio V2
      operationId: t2aV2
      parameters:
        - name: Content-Type
          in: header
          required: true
          description: 请求体的媒介类型，请设置为 `application/json`，确保请求数据的格式为 JSON
          schema:
            type: string
            enum:
              - application/json
            default: application/json
      requestBody:
        description: ''
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/T2aV2Req'
            examples:
              非流式:
                value:
                  model: speech-2.8-hd
                  text: 今天是不是很开心呀(laughs)，当然了！
                  stream: false
                  voice_setting:
                    voice_id: male-qn-qingse
                    speed: 1
                    vol: 1
                    pitch: 0
                    emotion: happy
                  pronunciation_dict:
                    tone:
                      - 处理/(chu3)(li3)
                      - 危险/dangerous
                  audio_setting:
                    sample_rate: 32000
                    bitrate: 128000
                    format: mp3
                    channel: 1
                  subtitle_enable: false
              流式:
                value:
                  model: speech-2.8-hd
                  text: 今天是不是很开心呀(laughs)，当然了！
                  stream: true
                  voice_setting:
                    voice_id: male-qn-qingse
                    speed: 1
                    vol: 1
                    pitch: 0
                    emotion: happy
                  pronunciation_dict:
                    tone:
                      - 处理/(chu3)(li3)
                      - 危险/dangerous
                  audio_setting:
                    sample_rate: 32000
                    bitrate: 128000
                    format: mp3
                    channel: 1
                  subtitle_enable: false
        required: true
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/T2aV2Resp'
              examples:
                非流式:
                  value:
                    data:
                      audio: <hex编码的audio>
                      status: 2
                    extra_info:
                      audio_length: 9900
                      audio_sample_rate: 32000
                      audio_size: 160323
                      bitrate: 128000
                      word_count: 52
                      invisible_character_ratio: 0
                      usage_characters: 26
                      audio_format: mp3
                      audio_channel: 1
                    trace_id: 01b8bf9bb7433cc75c18eee6cfa8fe21
                    base_resp:
                      status_code: 0
                      status_msg: success
                流式:
                  value:
                    - data:
                        audio: hex编码的audio_chunk1
                        status: 1
                      trace_id: 01b8bf9bb7433cc75c18eee6cfa8fe21
                      base_resp:
                        status_code: 0
                        status_msg: ''
                    - data:
                        audio: hex编码的audio_chunk2
                        status: 1
                      trace_id: 01b8bf9bb7433cc75c18eee6cfa8fe21
                      base_resp:
                        status_code: 0
                        status_msg: ''
                    - data:
                        audio: hex编码的audio
                        status: 2
                      extra_info:
                        audio_length: 6931
                        audio_sample_rate: 32000
                        audio_size: 111789
                        bitrate: 128000
                        word_count: 112
                        invisible_character_ratio: 0
                        usage_characters: 112
                        audio_format: mp3
                        audio_channel: 1
                      trace_id: 04ece790375f3ca2edbb44e8c4c200bf
                      base_resp:
                        status_code: 0
                        status_msg: success
            text/event-stream:
              schema:
                $ref: '#/components/schemas/T2aV2Resp'
              examples:
                流式:
                  value:
                    - data:
                        audio: hex编码的audio_chunk1
                        status: 1
                      trace_id: 01b8bf9bb7433cc75c18eee6cfa8fe21
                      base_resp:
                        status_code: 0
                        status_msg: ''
                    - data:
                        audio: hex编码的audio_chunk2
                        status: 1
                      trace_id: 01b8bf9bb7433cc75c18eee6cfa8fe21
                      base_resp:
                        status_code: 0
                        status_msg: ''
                    - data:
                        audio: hex编码的audio
                        status: 2
                      extra_info:
                        audio_length: 6931
                        audio_sample_rate: 32000
                        audio_size: 111789
                        bitrate: 128000
                        word_count: 112
                        invisible_character_ratio: 0
                        usage_characters: 112
                        audio_format: mp3
                        audio_channel: 1
                      trace_id: 04ece790375f3ca2edbb44e8c4c200bf
                      base_resp:
                        status_code: 0
                        status_msg: success
components:
  schemas:
    T2aV2Req:
      type: object
      required:
        - model
        - text
      properties:
        model:
          type: string
          description: >-
            请求的模型版本，可选范围：`speech-2.8-hd`, `speech-2.8-turbo`, `speech-2.6-hd`,
            `speech-2.6-turbo`, `speech-02-hd`, `speech-02-turbo`,
            `speech-01-hd`, `speech-01-turbo`.
          enum:
            - speech-2.8-hd
            - speech-2.8-turbo
            - speech-2.6-hd
            - speech-2.6-turbo
            - speech-02-hd
            - speech-02-turbo
            - speech-01-hd
            - speech-01-turbo
        text:
          type: string
          description: >-
            需要合成语音的文本，长度限制小于 10000 字符，若文本长度大于 3000 字符，推荐使用流式输出

            - 段落切换用换行符标记

            - 停顿控制：支持自定义文本之间的语音时间间隔，以实现自定义文本语音停顿时间的效果。使用方式：在文本中增加`<#x#>`标记，`x`
            为停顿时长（单位：秒），范围 [0.01,
            99.99]，最多保留两位小数。文本间隔时间需设置在两个可以语音发音的文本之间，不可连续使用多个停顿标记

            - 语气词标签：仅当模型选择 `speech-2.8-hd` 或 `speech-2.8-turbo`
            时，支持在文本中插入语气词标签。支持的语气词：`(laughs)`（笑声）、`(chuckle)`（轻笑）、`(coughs)`（咳嗽）、`(clear-throat)`（清嗓子）、`(groans)`（呻吟）、`(breath)`（正常换气）、`(pant)`（喘气）、`(inhale)`（吸气）、`(exhale)`（呼气）、`(gasps)`（倒吸气）、`(sniffs)`（吸鼻子）、`(sighs)`（叹气）、`(snorts)`（喷鼻息）、`(burps)`（打嗝）、`(lip-smacking)`（咂嘴）、`(humming)`（哼唱）、`(hissing)`（嘶嘶声）、`(emm)`（嗯）、`(sneezes)`（喷嚏）
        stream:
          type: boolean
          description: 控制是否流式输出。默认 false，即不开启流式
        stream_options:
          $ref: '#/components/schemas/T2AStreamOption'
        voice_setting:
          $ref: '#/components/schemas/T2AVoiceSetting'
        audio_setting:
          $ref: '#/components/schemas/T2AAudioSetting'
        pronunciation_dict:
          $ref: '#/components/schemas/PronunciationDict'
        timbre_weights:
          type: array
          items:
            $ref: '#/components/schemas/TimbreWeights'
        language_boost:
          type: string
          description: |-
            是否增强对指定的小语种和方言的识别能力。默认值为 `null`，可设置为 `auto` 让模型自主判断。

            注意：speech-01 和 speech-02 系列模型暂不支持 Persian、Filipino、Tamil 这三个语种。
          enum:
            - Chinese
            - Chinese,Yue
            - English
            - Arabic
            - Russian
            - Spanish
            - French
            - Portuguese
            - German
            - Turkish
            - Dutch
            - Ukrainian
            - Vietnamese
            - Indonesian
            - Japanese
            - Italian
            - Korean
            - Thai
            - Polish
            - Romanian
            - Greek
            - Czech
            - Finnish
            - Hindi
            - Bulgarian
            - Danish
            - Hebrew
            - Malay
            - Persian
            - Slovak
            - Swedish
            - Croatian
            - Filipino
            - Hungarian
            - Norwegian
            - Slovenian
            - Catalan
            - Nynorsk
            - Tamil
            - Afrikaans
            - auto
          default: null
        voice_modify:
          $ref: '#/components/schemas/VoiceModify'
        subtitle_enable:
          type: boolean
          description: >-
            控制是否开启字幕服务，默认值为 false。仅对 `speech-2.8-hd`, `speech-2.8-turbo`,
            `speech-2.6-hd`, `speech-2.6-turbo`, `speech-02-hd`,
            `speech-02-turbo`, `speech-01-hd`, `speech-01-turbo` 模型有效
          default: false
        output_format:
          type: string
          description: >-
            控制输出结果形式的参数，可选值范围为[`url`, `hex`]，默认值为 `hex` 。该参数仅在非流式场景生效，流式场景仅支持返回
            hex 形式。返回的 url 有效期为 24 小时
          enum:
            - url
            - hex
          default: hex
        aigc_watermark:
          type: boolean
          description: 控制在合成音频的末尾添加音频节奏标识，默认值为 False。该参数仅对非流式合成生效
          default: false
    T2aV2Resp:
      type: object
      properties:
        data:
          type: object
          description: 返回的合成数据对象，可能为 null，需进行非空判断
          properties:
            audio:
              type: string
              description: 合成后的音频数据，采用 hex 编码，格式与请求中指定的输出格式一致
            subtitle_file:
              type: string
              description: 合成的字幕下载链接。音频文件对应的字幕，精确到句（不超过 50 字），单位为毫秒，格式为 json
            status:
              type: integer
              description: 当前音频流状态：1 表示合成中，2 表示合成结束
        trace_id:
          type: string
          description: 本次会话的 id，用于在咨询/反馈时帮助定位问题
        extra_info:
          type: object
          description: 音频的附加信息
          properties:
            audio_length:
              type: integer
              format: int64
              description: 音频时长（毫秒）
            audio_sample_rate:
              type: integer
              format: int64
              description: 音频采样率
            audio_size:
              type: integer
              format: int64
              description: 音频文件大小（字节）
            bitrate:
              type: integer
              format: int64
              description: 音频比特率
            audio_format:
              type: string
              description: 生成音频文件的格式。取值范围 `[mp3, pcm, flac]`
              enum:
                - mp3
                - pcm
                - flac
            audio_channel:
              type: integer
              format: int64
              description: 生成音频声道数,1：单声道，2：双声道
            invisible_character_ratio:
              type: number
              format: float64
              description: 非法字符占比.非法字符不超过 10%（包含 10%），音频会正常生成,并返回非法字符占比数据；如超过 10% 将进行报错
            usage_characters:
              type: integer
              format: int64
              description: 计费字符数
            word_count:
              type: integer
              format: int64
              description: 已发音的字数统计，包含汉字、数字、字母，不包含标点符号
        base_resp:
          type: object
          description: 本次请求的状态码和详情
          properties:
            status_code:
              type: integer
              format: int64
              description: |-
                状态码。

                 您可在header中获取本次会话的trace_id，用于在咨询/反馈时帮助定位问题 

                - `0`: 请求结果正常
                - `1000`: 未知错误
                - `1001`: 超时
                - `1002`: 触发限流
                - `1004`: 鉴权失败
                - `1039`: 触发 TPM 限流
                - `1042`: 非法字符超过 10%
                - `2013`: 输入参数信息不正常

                更多内容可查看 [错误码查询列表](/api-reference/errorcode) 了解详情
            status_msg:
              type: string
              description: 状态详情
    T2AStreamOption:
      type: object
      properties:
        exclude_aggregated_audio:
          type: boolean
          description: >-
            设置最后一个 chunk 是否包含拼接后的语音 hex 数据。默认值为 False，即最后一个 chunk 中包含拼接后的完整语音
            hex 数据
    T2AVoiceSetting:
      type: object
      required:
        - voice_id
      properties:
        voice_id:
          type: string
          description: "合成音频的音色编号。若需要设置混合音色，请设置 timbre_weights 参数，本参数设置为空值。支持系统音色、复刻音色以及文生音色三种类型，以下是部分最新的系统音色（ID），可查看 [系统音色列表](/faq/system-voice-id) 或使用 [查询可用音色 API](/api-reference/voice-management-get) 查询系统支持的全部音色\n\n - **中文**:\n\t- moss_audio_ce44fc67-7ce3-11f0-8de5-96e35d26fb85\n\t- moss_audio_aaa1346a-7ce7-11f0-8e61-2e6e3c7ee85d\n\t- Chinese (Mandarin)_Lyrical_Voice\n\t- Chinese (Mandarin)_HK_Flight_Attendant\n- **英文**:\n\t- English_Graceful_Lady\n\t- English_Insightful_Speaker\n\t- English_radiant_girl\n\t- English_Persuasive_Man\n\t- moss_audio_6dc281eb-713c-11f0-a447-9613c873494c\n\t- moss_audio_570551b1-735c-11f0-b236-0adeeecad052\n\t- moss_audio_ad5baf92-735f-11f0-8263-fe5a2fe98ec8\n\t- English_Lucky_Robot\n- **日文**:\n\t- Japanese_Whisper_Belle\n\t- moss_audio_24875c4a-7be4-11f0-9359-4e72c55db738\n\t- moss_audio_7f4ee608-78ea-11f0-bb73-1e2a4cfcd245\n\t- moss_audio_c1a6a3ac-7be6-11f0-8e8e-36b92fbb4f95"
        speed:
          type: number
          format: float
          description: 合成音频的语速，取值越大，语速越快。取值范围 `[0.5,2]`，默认值为1.0
          minimum: 0.5
          maximum: 2
          default: 1
        vol:
          type: number
          format: float
          description: 合成音频的音量，取值越大，音量越高。取值范围 `(0,10]`，默认值为 1.0
          exclusiveMinimum: 0
          maximum: 10
          default: 1
        pitch:
          type: integer
          description: 合成音频的语调，取值范围 `[-12,12]`，默认值为 0，其中 0 为原音色输出
          minimum: -12
          maximum: 12
          default: 0
        emotion:
          type: string
          description: "控制合成语音的情绪，参数范围 `[\"happy\", \"sad\", \"angry\", \"fearful\", \"disgusted\", \"surprised\", \"calm\", \"fluent\", \"whisper\"]`，分别对应 8 种情绪：高兴，悲伤，愤怒，害怕，厌恶，惊讶，中性，生动，低语 \r\n- 模型会根据输入文本自动匹配合适的情绪，一般无需手动指定  \r\n- 该参数仅对 `speech-2.8-hd`, `speech-2.8-turbo`, `speech-2.6-hd`, `speech-2.6-turbo`, `speech-02-hd`, `speech-02-turbo`, `speech-01-hd`, `speech-01-turbo` 模型生效 \r\n- 选项 `fluent`, `whisper` 仅对 `speech-2.6-turbo`, `speech-2.6-hd` 模型生效，`speech-2.8-hd`, `speech-2.8-turbo` 模型不支持 `whisper`"
          enum:
            - happy
            - sad
            - angry
            - fearful
            - disgusted
            - surprised
            - calm
            - fluent
            - whisper
        text_normalization:
          type: boolean
          description: 是否启用中文、英语文本规范化，开启后可提升数字阅读场景的性能，但会略微增加延迟，默认值为 false
          default: false
        latex_read:
          type: boolean
          description: >-
            控制是否朗读 latex 公式，默认为 false

            **需注意**:

            - 仅支持中文，开启该参数后，`language_boost` 参数会被设置为 `Chinese`

            - 请求中的公式需要在公式的首尾加上 `$$`

            - 请求中公式若有 `"\"`，需转义成 `"\\"`.


            示例：一元二次方程根的基本公式

            ![The quadratic
            formula](https://filecdn.minimax.chat/public/d6f62e9a-cd3f-4f55-a237-257eef531683.png)


            应表示为 `$$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}$$`
          default: false
    T2AAudioSetting:
      type: object
      properties:
        sample_rate:
          type: integer
          format: int64
          description: 生成音频的采样率。可选范围`[8000，16000，22050，24000，32000，44100]`，默认为 `32000`
        bitrate:
          type: integer
          format: int64
          description: >-
            生成音频的比特率。可选范围`[32000，64000，128000，256000]`，默认值为 `128000`。该参数仅对 `mp3`
            格式的音频生效
        format:
          type: string
          description: 生成音频的格式，`wav` 仅在非流式输出下支持
          enum:
            - mp3
            - pcm
            - flac
            - wav
          default: mp3
        channel:
          type: integer
          format: int64
          description: 生成音频的声道数。可选范围：`[1,2]`，其中 `1` 为单声道，`2` 为双声道，默认值为 1
        force_cbr:
          type: boolean
          description: |-
            对于音频恒定比特率（cbr）控制，可选 `false`、 `true`。当此参数设置为 `true`，将以恒定比特率方式进行音频编码。
            注意：本参数仅当音频设置为**流式**输出，且音频格式为 `mp3` 时生效。
          default: false
    PronunciationDict:
      type: object
      properties:
        tone:
          type: array
          description: |-
            定义需要特殊标注的文字或符号对应的注音或发音替换规则。在中文文本中，声调用数字表示：
            一声为 1，二声为 2，三声为 3，四声为 4，轻声为 5
            示例如下：
            `["燕少飞/(yan4)(shao3)(fei1)", "omg/oh my god"]`
          items:
            type: string
    TimbreWeights:
      type: object
      required:
        - voice_id
        - weight
      properties:
        voice_id:
          type: string
          description: >-
            合成音频的音色编号，须和weight参数同步填写。支持系统音色、复刻音色以及文生音色三种类型。系统支持的全部音色可查看
            [系统音色列表](/faq/system-voice-id)，也可使用 [查询可用音色
            API](/api-reference/voice-management-get) 查询系统支持的全部音色
        weight:
          type: integer
          format: int64
          description: >-
            合成音频各音色所占的权重，须与 voice_id 同步填写。可选值范围为[1, 100]，最多支持 4
            种音色混合，单一音色取值占比越高，合成音色与该音色相似度越高.


            ```json dark

            "timbre_weights": [
              {
                "voice_id": "female-chengshu",
                "weight": 30
              },
              {
                "voice_id": "female-tianmei",
                "weight": 70
              }
            ]

            ```
          minimum: 1
          maximum: 100
    VoiceModify:
      type: object
      description: |-
        声音效果器设置，该参数支持的音频格式：
        - 非流式：`mp3`, `wav`, `flac`
        - 流式：`mp3`
      properties:
        pitch:
          type: integer
          description: >-
            音高调整（低沉/明亮），范围 [-100,100]，数值接近 -100，声音更低沉；接近 100，声音更明亮


            ![pitch
            adjustment](https://filecdn.minimax.chat/public/5d210c47-4236-4e81-893b-16cc1ef0302d.png)
          minimum: -100
          maximum: 100
        intensity:
          type: integer
          description: >-
            强度调整（力量感/柔和），范围 [-100,100]，数值接近 -100，声音更刚劲；接近 100，声音更轻柔


            ![intensity
            adjustment](https://filecdn.minimax.chat/public/862d493e-71d5-4d1f-b7c3-9ac51890631b.png)
          minimum: -100
          maximum: 100
        timbre:
          type: integer
          description: >-
            音色调整（磁性/清脆），范围 [-100,100]，数值接近 -100，声音更浑厚；数值接近 100，声音更清脆


            ![timbre
            adjustment](https://filecdn.minimax.chat/public/5f0e6cae-363a-452b-8d42-fbc4ef5a0510.png)
          minimum: -100
          maximum: 100
        sound_effects:
          type: string
          description: |-
            音效设置，单次仅能选择一种，可选值：
            1. spacious_echo（空旷回音）
            2. auditorium_echo（礼堂广播）
            3. lofi_telephone（电话失真）
            4. robotic（电音）
          enum:
            - spacious_echo
            - auditorium_echo
            - lofi_telephone
            - robotic
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: |-
        `HTTP: Bearer Auth`
         - Security Scheme Type: http
         - HTTP Authorization Scheme: Bearer API_key，用于验证账户信息，可在 [账户管理>接口密钥](https://platform.minimaxi.com/user-center/basic-information/interface-key) 中查看。

````


> ## Documentation Index
>
> Fetch the complete documentation index at: https://platform.minimaxi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 系统音色列表

> 本文档列举了MiniMax开放平台全部的系统音色，为您提供语音合成选择。参考以下表格的内容，可查阅目前全部的系统音色的ID(Voice ID)、名称及支持语言，方便开发者快速查询与调用。

参考以下表格的内容，可查阅目前全部的系统音色。


| 序号 | 语言          | 音色 ID (Voice ID)                          | 音色名称 (Voice Name)     |
| :--- | :------------ | :------------------------------------------ | :------------------------ |
| 1    | 中文 (普通话) | `male-qn-qingse`                            | 青涩青年音色              |
| 2    | 中文 (普通话) | `male-qn-jingying`                          | 精英青年音色              |
| 3    | 中文 (普通话) | `male-qn-badao`                             | 霸道青年音色              |
| 4    | 中文 (普通话) | `male-qn-daxuesheng`                        | 青年大学生音色            |
| 5    | 中文 (普通话) | `female-shaonv`                             | 少女音色                  |
| 6    | 中文 (普通话) | `female-yujie`                              | 御姐音色                  |
| 7    | 中文 (普通话) | `female-chengshu`                           | 成熟女性音色              |
| 8    | 中文 (普通话) | `female-tianmei`                            | 甜美女性音色              |
| 9    | 中文 (普通话) | `male-qn-qingse-jingpin`                    | 青涩青年音色-beta         |
| 10   | 中文 (普通话) | `male-qn-jingying-jingpin`                  | 精英青年音色-beta         |
| 11   | 中文 (普通话) | `male-qn-badao-jingpin`                     | 霸道青年音色-beta         |
| 12   | 中文 (普通话) | `male-qn-daxuesheng-jingpin`                | 青年大学生音色-beta       |
| 13   | 中文 (普通话) | `female-shaonv-jingpin`                     | 少女音色-beta             |
| 14   | 中文 (普通话) | `female-yujie-jingpin`                      | 御姐音色-beta             |
| 15   | 中文 (普通话) | `female-chengshu-jingpin`                   | 成熟女性音色-beta         |
| 16   | 中文 (普通话) | `female-tianmei-jingpin`                    | 甜美女性音色-beta         |
| 17   | 中文 (普通话) | `clever_boy`                                | 聪明男童                  |
| 18   | 中文 (普通话) | `cute_boy`                                  | 可爱男童                  |
| 19   | 中文 (普通话) | `lovely_girl`                               | 萌萌女童                  |
| 20   | 中文 (普通话) | `cartoon_pig`                               | 卡通猪小琪                |
| 21   | 中文 (普通话) | `bingjiao_didi`                             | 病娇弟弟                  |
| 22   | 中文 (普通话) | `junlang_nanyou`                            | 俊朗男友                  |
| 23   | 中文 (普通话) | `chunzhen_xuedi`                            | 纯真学弟                  |
| 24   | 中文 (普通话) | `lengdan_xiongzhang`                        | 冷淡学长                  |
| 25   | 中文 (普通话) | `badao_shaoye`                              | 霸道少爷                  |
| 26   | 中文 (普通话) | `tianxin_xiaoling`                          | 甜心小玲                  |
| 27   | 中文 (普通话) | `qiaopi_mengmei`                            | 俏皮萌妹                  |
| 28   | 中文 (普通话) | `wumei_yujie`                               | 妩媚御姐                  |
| 29   | 中文 (普通话) | `diadia_xuemei`                             | 嗲嗲学妹                  |
| 30   | 中文 (普通话) | `danya_xuejie`                              | 淡雅学姐                  |
| 31   | 中文 (普通话) | `Chinese (Mandarin)_Reliable_Executive`     | 沉稳高管                  |
| 32   | 中文 (普通话) | `Chinese (Mandarin)_News_Anchor`            | 新闻女声                  |
| 33   | 中文 (普通话) | `Chinese (Mandarin)_Mature_Woman`           | 傲娇御姐                  |
| 34   | 中文 (普通话) | `Chinese (Mandarin)_Unrestrained_Young_Man` | 不羁青年                  |
| 35   | 中文 (普通话) | `Arrogant_Miss`                             | 嚣张小姐                  |
| 36   | 中文 (普通话) | `Robot_Armor`                               | 机械战甲                  |
| 37   | 中文 (普通话) | `Chinese (Mandarin)_Kind-hearted_Antie`     | 热心大婶                  |
| 38   | 中文 (普通话) | `Chinese (Mandarin)_HK_Flight_Attendant`    | 港普空姐                  |
| 39   | 中文 (普通话) | `Chinese (Mandarin)_Humorous_Elder`         | 搞笑大爷                  |
| 40   | 中文 (普通话) | `Chinese (Mandarin)_Gentleman`              | 温润男声                  |
| 41   | 中文 (普通话) | `Chinese (Mandarin)_Warm_Bestie`            | 温暖闺蜜                  |
| 42   | 中文 (普通话) | `Chinese (Mandarin)_Male_Announcer`         | 播报男声                  |
| 43   | 中文 (普通话) | `Chinese (Mandarin)_Sweet_Lady`             | 甜美女声                  |
| 44   | 中文 (普通话) | `Chinese (Mandarin)_Southern_Young_Man`     | 南方小哥                  |
| 45   | 中文 (普通话) | `Chinese (Mandarin)_Wise_Women`             | 阅历姐姐                  |
| 46   | 中文 (普通话) | `Chinese (Mandarin)_Gentle_Youth`           | 温润青年                  |
| 47   | 中文 (普通话) | `Chinese (Mandarin)_Warm_Girl`              | 温暖少女                  |
| 48   | 中文 (普通话) | `Chinese (Mandarin)_Kind-hearted_Elder`     | 花甲奶奶                  |
| 49   | 中文 (普通话) | `Chinese (Mandarin)_Cute_Spirit`            | 憨憨萌兽                  |
| 50   | 中文 (普通话) | `Chinese (Mandarin)_Radio_Host`             | 电台男主播                |
| 51   | 中文 (普通话) | `Chinese (Mandarin)_Lyrical_Voice`          | 抒情男声                  |
| 52   | 中文 (普通话) | `Chinese (Mandarin)_Straightforward_Boy`    | 率真弟弟                  |
| 53   | 中文 (普通话) | `Chinese (Mandarin)_Sincere_Adult`          | 真诚青年                  |
| 54   | 中文 (普通话) | `Chinese (Mandarin)_Gentle_Senior`          | 温柔学姐                  |
| 55   | 中文 (普通话) | `Chinese (Mandarin)_Stubborn_Friend`        | 嘴硬竹马                  |
| 56   | 中文 (普通话) | `Chinese (Mandarin)_Crisp_Girl`             | 清脆少女                  |
| 57   | 中文 (普通话) | `Chinese (Mandarin)_Pure-hearted_Boy`       | 清澈邻家弟弟              |
| 58   | 中文 (普通话) | `Chinese (Mandarin)_Soft_Girl`              | 柔和少女                  |
| 59   | 中文 (粤语)   | `Cantonese_ProfessionalHost（F)`            | 专业女主持                |
| 60   | 中文 (粤语)   | `Cantonese_GentleLady`                      | 温柔女声                  |
| 61   | 中文 (粤语)   | `Cantonese_ProfessionalHost（M)`            | 专业男主持                |
| 62   | 中文 (粤语)   | `Cantonese_PlayfulMan`                      | 活泼男声                  |
| 63   | 中文 (粤语)   | `Cantonese_CuteGirl`                        | 可爱女孩                  |
| 64   | 中文 (粤语)   | `Cantonese_KindWoman`                       | 善良女声                  |
| 65   | 英文          | `Santa_Claus `                              | Santa Claus               |
| 66   | 英文          | `Grinch`                                    | Grinch                    |
| 67   | 英文          | `Rudolph`                                   | Rudolph                   |
| 68   | 英文          | `Arnold`                                    | Arnold                    |
| 69   | 英文          | `Charming_Santa`                            | Charming Santa            |
| 70   | 英文          | `Charming_Lady`                             | Charming Lady             |
| 71   | 英文          | `Sweet_Girl`                                | Sweet Girl                |
| 72   | 英文          | `Cute_Elf`                                  | Cute Elf                  |
| 73   | 英文          | `Attractive_Girl`                           | Attractive Girl           |
| 74   | 英文          | `Serene_Woman`                              | Serene Woman              |
| 75   | 英文          | `English_Trustworthy_Man`                   | Trustworthy Man           |
| 76   | 英文          | `English_Graceful_Lady`                     | Graceful Lady             |
| 77   | 英文          | `English_Aussie_Bloke`                      | Aussie Bloke              |
| 78   | 英文          | `English_Whispering_girl`                   | Whispering girl           |
| 79   | 英文          | `English_Diligent_Man`                      | Diligent Man              |
| 80   | 英文          | `English_Gentle-voiced_man`                 | Gentle-voiced man         |
| 81   | 日文          | `Japanese_IntellectualSenior`               | Intellectual Senior       |
| 82   | 日文          | `Japanese_DecisivePrincess`                 | Decisive Princess         |
| 83   | 日文          | `Japanese_LoyalKnight`                      | Loyal Knight              |
| 84   | 日文          | `Japanese_DominantMan`                      | Dominant Man              |
| 85   | 日文          | `Japanese_SeriousCommander`                 | Serious Commander         |
| 86   | 日文          | `Japanese_ColdQueen`                        | Cold Queen                |
| 87   | 日文          | `Japanese_DependableWoman`                  | Dependable Woman          |
| 88   | 日文          | `Japanese_GentleButler`                     | Gentle Butler             |
| 89   | 日文          | `Japanese_KindLady`                         | Kind Lady                 |
| 90   | 日文          | `Japanese_CalmLady`                         | Calm Lady                 |
| 91   | 日文          | `Japanese_OptimisticYouth`                  | Optimistic Youth          |
| 92   | 日文          | `Japanese_GenerousIzakayaOwner`             | Generous Izakaya Owner    |
| 93   | 日文          | `Japanese_SportyStudent`                    | Sporty Student            |
| 94   | 日文          | `Japanese_InnocentBoy`                      | Innocent Boy              |
| 95   | 日文          | `Japanese_GracefulMaiden`                   | Graceful Maiden           |
| 96   | 韩文          | `Korean_SweetGirl`                          | Sweet Girl                |
| 97   | 韩文          | `Korean_CheerfulBoyfriend`                  | Cheerful Boyfriend        |
| 98   | 韩文          | `Korean_EnchantingSister`                   | Enchanting Sister         |
| 99   | 韩文          | `Korean_ShyGirl`                            | Shy Girl                  |
| 100  | 韩文          | `Korean_ReliableSister`                     | Reliable Sister           |
| 101  | 韩文          | `Korean_StrictBoss`                         | Strict Boss               |
| 102  | 韩文          | `Korean_SassyGirl`                          | Sassy Girl                |
| 103  | 韩文          | `Korean_ChildhoodFriendGirl`                | Childhood Friend Girl     |
| 104  | 韩文          | `Korean_PlayboyCharmer`                     | Playboy Charmer           |
| 105  | 韩文          | `Korean_ElegantPrincess`                    | Elegant Princess          |
| 106  | 韩文          | `Korean_BraveFemaleWarrior`                 | Brave Female Warrior      |
| 107  | 韩文          | `Korean_BraveYouth`                         | Brave Youth               |
| 108  | 韩文          | `Korean_CalmLady`                           | Calm Lady                 |
| 109  | 韩文          | `Korean_EnthusiasticTeen`                   | Enthusiastic Teen         |
| 110  | 韩文          | `Korean_SoothingLady`                       | Soothing Lady             |
| 111  | 韩文          | `Korean_IntellectualSenior`                 | Intellectual Senior       |
| 112  | 韩文          | `Korean_LonelyWarrior`                      | Lonely Warrior            |
| 113  | 韩文          | `Korean_MatureLady`                         | Mature Lady               |
| 114  | 韩文          | `Korean_InnocentBoy`                        | Innocent Boy              |
| 115  | 韩文          | `Korean_CharmingSister`                     | Charming Sister           |
| 116  | 韩文          | `Korean_AthleticStudent`                    | Athletic Student          |
| 117  | 韩文          | `Korean_BraveAdventurer`                    | Brave Adventurer          |
| 118  | 韩文          | `Korean_CalmGentleman`                      | Calm Gentleman            |
| 119  | 韩文          | `Korean_WiseElf`                            | Wise Elf                  |
| 120  | 韩文          | `Korean_CheerfulCoolJunior`                 | Cheerful Cool Junior      |
| 121  | 韩文          | `Korean_DecisiveQueen`                      | Decisive Queen            |
| 122  | 韩文          | `Korean_ColdYoungMan`                       | Cold Young Man            |
| 123  | 韩文          | `Korean_MysteriousGirl`                     | Mysterious Girl           |
| 124  | 韩文          | `Korean_QuirkyGirl`                         | Quirky Girl               |
| 125  | 韩文          | `Korean_ConsiderateSenior`                  | Considerate Senior        |
| 126  | 韩文          | `Korean_CheerfulLittleSister`               | Cheerful Little Sister    |
| 127  | 韩文          | `Korean_DominantMan`                        | Dominant Man              |
| 128  | 韩文          | `Korean_AirheadedGirl`                      | Airheaded Girl            |
| 129  | 韩文          | `Korean_ReliableYouth`                      | Reliable Youth            |
| 130  | 韩文          | `Korean_FriendlyBigSister`                  | Friendly Big Sister       |
| 131  | 韩文          | `Korean_GentleBoss`                         | Gentle Boss               |
| 132  | 韩文          | `Korean_ColdGirl`                           | Cold Girl                 |
| 133  | 韩文          | `Korean_HaughtyLady`                        | Haughty Lady              |
| 134  | 韩文          | `Korean_CharmingElderSister`                | Charming Elder Sister     |
| 135  | 韩文          | `Korean_IntellectualMan`                    | Intellectual Man          |
| 136  | 韩文          | `Korean_CaringWoman`                        | Caring Woman              |
| 137  | 韩文          | `Korean_WiseTeacher`                        | Wise Teacher              |
| 138  | 韩文          | `Korean_ConfidentBoss`                      | Confident Boss            |
| 139  | 韩文          | `Korean_AthleticGirl`                       | Athletic Girl             |
| 140  | 韩文          | `Korean_PossessiveMan`                      | Possessive Man            |
| 141  | 韩文          | `Korean_GentleWoman`                        | Gentle Woman              |
| 142  | 韩文          | `Korean_CockyGuy`                           | Cocky Guy                 |
| 143  | 韩文          | `Korean_ThoughtfulWoman`                    | Thoughtful Woman          |
| 144  | 韩文          | `Korean_OptimisticYouth`                    | Optimistic Youth          |
| 145  | 西班牙文      | `Spanish_SereneWoman`                       | Serene Woman              |
| 146  | 西班牙文      | `Spanish_MaturePartner`                     | Mature Partner            |
| 147  | 西班牙文      | `Spanish_CaptivatingStoryteller`            | Captivating Storyteller   |
| 148  | 西班牙文      | `Spanish_Narrator`                          | Narrator                  |
| 149  | 西班牙文      | `Spanish_WiseScholar`                       | Wise Scholar              |
| 150  | 西班牙文      | `Spanish_Kind-heartedGirl`                  | Kind-hearted Girl         |
| 151  | 西班牙文      | `Spanish_DeterminedManager`                 | Determined Manager        |
| 152  | 西班牙文      | `Spanish_BossyLeader`                       | Bossy Leader              |
| 153  | 西班牙文      | `Spanish_ReservedYoungMan`                  | Reserved Young Man        |
| 154  | 西班牙文      | `Spanish_ConfidentWoman`                    | Confident Woman           |
| 155  | 西班牙文      | `Spanish_ThoughtfulMan`                     | Thoughtful Man            |
| 156  | 西班牙文      | `Spanish_Strong-WilledBoy`                  | Strong-willed Boy         |
| 157  | 西班牙文      | `Spanish_SophisticatedLady`                 | Sophisticated Lady        |
| 158  | 西班牙文      | `Spanish_RationalMan`                       | Rational Man              |
| 159  | 西班牙文      | `Spanish_AnimeCharacter`                    | Anime Character           |
| 160  | 西班牙文      | `Spanish_Deep-tonedMan`                     | Deep-toned Man            |
| 161  | 西班牙文      | `Spanish_Fussyhostess`                      | Fussy hostess             |
| 162  | 西班牙文      | `Spanish_SincereTeen`                       | Sincere Teen              |
| 163  | 西班牙文      | `Spanish_FrankLady`                         | Frank Lady                |
| 164  | 西班牙文      | `Spanish_Comedian`                          | Comedian                  |
| 165  | 西班牙文      | `Spanish_Debator`                           | Debator                   |
| 166  | 西班牙文      | `Spanish_ToughBoss`                         | Tough Boss                |
| 167  | 西班牙文      | `Spanish_Wiselady`                          | Wise Lady                 |
| 168  | 西班牙文      | `Spanish_Steadymentor`                      | Steady Mentor             |
| 169  | 西班牙文      | `Spanish_Jovialman`                         | Jovial Man                |
| 170  | 西班牙文      | `Spanish_SantaClaus`                        | Santa Claus               |
| 171  | 西班牙文      | `Spanish_Rudolph`                           | Rudolph                   |
| 172  | 西班牙文      | `Spanish_Intonategirl`                      | Intonate Girl             |
| 173  | 西班牙文      | `Spanish_Arnold`                            | Arnold                    |
| 174  | 西班牙文      | `Spanish_Ghost`                             | Ghost                     |
| 175  | 西班牙文      | `Spanish_HumorousElder`                     | Humorous Elder            |
| 176  | 西班牙文      | `Spanish_EnergeticBoy`                      | Energetic Boy             |
| 177  | 西班牙文      | `Spanish_WhimsicalGirl`                     | Whimsical Girl            |
| 178  | 西班牙文      | `Spanish_StrictBoss`                        | Strict Boss               |
| 179  | 西班牙文      | `Spanish_ReliableMan`                       | Reliable Man              |
| 180  | 西班牙文      | `Spanish_SereneElder`                       | Serene Elder              |
| 181  | 西班牙文      | `Spanish_AngryMan`                          | Angry Man                 |
| 182  | 西班牙文      | `Spanish_AssertiveQueen`                    | Assertive Queen           |
| 183  | 西班牙文      | `Spanish_CaringGirlfriend`                  | Caring Girlfriend         |
| 184  | 西班牙文      | `Spanish_PowerfulSoldier`                   | Powerful Soldier          |
| 185  | 西班牙文      | `Spanish_PassionateWarrior`                 | Passionate Warrior        |
| 186  | 西班牙文      | `Spanish_ChattyGirl`                        | Chatty Girl               |
| 187  | 西班牙文      | `Spanish_RomanticHusband`                   | Romantic Husband          |
| 188  | 西班牙文      | `Spanish_CompellingGirl`                    | Compelling Girl           |
| 189  | 西班牙文      | `Spanish_PowerfulVeteran`                   | Powerful Veteran          |
| 190  | 西班牙文      | `Spanish_SensibleManager`                   | Sensible Manager          |
| 191  | 西班牙文      | `Spanish_ThoughtfulLady`                    | Thoughtful Lady           |
| 192  | 葡萄牙文      | `Portuguese_SentimentalLady`                | Sentimental Lady          |
| 193  | 葡萄牙文      | `Portuguese_BossyLeader`                    | Bossy Leader              |
| 194  | 葡萄牙文      | `Portuguese_Wiselady`                       | Wise lady                 |
| 195  | 葡萄牙文      | `Portuguese_Strong-WilledBoy`               | Strong-willed Boy         |
| 196  | 葡萄牙文      | `Portuguese_Deep-VoicedGentleman`           | Deep-voiced Gentleman     |
| 197  | 葡萄牙文      | `Portuguese_UpsetGirl`                      | Upset Girl                |
| 198  | 葡萄牙文      | `Portuguese_PassionateWarrior`              | Passionate Warrior        |
| 199  | 葡萄牙文      | `Portuguese_AnimeCharacter`                 | Anime Character           |
| 200  | 葡萄牙文      | `Portuguese_ConfidentWoman`                 | Confident Woman           |
| 201  | 葡萄牙文      | `Portuguese_AngryMan`                       | Angry Man                 |
| 202  | 葡萄牙文      | `Portuguese_CaptivatingStoryteller`         | Captivating Storyteller   |
| 203  | 葡萄牙文      | `Portuguese_Godfather`                      | Godfather                 |
| 204  | 葡萄牙文      | `Portuguese_ReservedYoungMan`               | Reserved Young Man        |
| 205  | 葡萄牙文      | `Portuguese_SmartYoungGirl`                 | Smart Young Girl          |
| 206  | 葡萄牙文      | `Portuguese_Kind-heartedGirl`               | Kind-hearted Girl         |
| 207  | 葡萄牙文      | `Portuguese_Pompouslady`                    | Pompous lady              |
| 208  | 葡萄牙文      | `Portuguese_Grinch`                         | Grinch                    |
| 209  | 葡萄牙文      | `Portuguese_Debator`                        | Debator                   |
| 210  | 葡萄牙文      | `Portuguese_SweetGirl`                      | Sweet Girl                |
| 211  | 葡萄牙文      | `Portuguese_AttractiveGirl`                 | Attractive Girl           |
| 212  | 葡萄牙文      | `Portuguese_ThoughtfulMan`                  | Thoughtful Man            |
| 213  | 葡萄牙文      | `Portuguese_PlayfulGirl`                    | Playful Girl              |
| 214  | 葡萄牙文      | `Portuguese_GorgeousLady`                   | Gorgeous Lady             |
| 215  | 葡萄牙文      | `Portuguese_LovelyLady`                     | Lovely Lady               |
| 216  | 葡萄牙文      | `Portuguese_SereneWoman`                    | Serene Woman              |
| 217  | 葡萄牙文      | `Portuguese_SadTeen`                        | Sad Teen                  |
| 218  | 葡萄牙文      | `Portuguese_MaturePartner`                  | Mature Partner            |
| 219  | 葡萄牙文      | `Portuguese_Comedian`                       | Comedian                  |
| 220  | 葡萄牙文      | `Portuguese_NaughtySchoolgirl`              | Naughty Schoolgirl        |
| 221  | 葡萄牙文      | `Portuguese_Narrator`                       | Narrator                  |
| 222  | 葡萄牙文      | `Portuguese_ToughBoss`                      | Tough Boss                |
| 223  | 葡萄牙文      | `Portuguese_Fussyhostess`                   | Fussy hostess             |
| 224  | 葡萄牙文      | `Portuguese_Dramatist`                      | Dramatist                 |
| 225  | 葡萄牙文      | `Portuguese_Steadymentor`                   | Steady Mentor             |
| 226  | 葡萄牙文      | `Portuguese_Jovialman`                      | Jovial Man                |
| 227  | 葡萄牙文      | `Portuguese_CharmingQueen`                  | Charming Queen            |
| 228  | 葡萄牙文      | `Portuguese_SantaClaus`                     | Santa Claus               |
| 229  | 葡萄牙文      | `Portuguese_Rudolph`                        | Rudolph                   |
| 230  | 葡萄牙文      | `Portuguese_Arnold`                         | Arnold                    |
| 231  | 葡萄牙文      | `Portuguese_CharmingSanta`                  | Charming Santa            |
| 232  | 葡萄牙文      | `Portuguese_CharmingLady`                   | Charming Lady             |
| 233  | 葡萄牙文      | `Portuguese_Ghost`                          | Ghost                     |
| 234  | 葡萄牙文      | `Portuguese_HumorousElder`                  | Humorous Elder            |
| 235  | 葡萄牙文      | `Portuguese_CalmLeader`                     | Calm Leader               |
| 236  | 葡萄牙文      | `Portuguese_GentleTeacher`                  | Gentle Teacher            |
| 237  | 葡萄牙文      | `Portuguese_EnergeticBoy`                   | Energetic Boy             |
| 238  | 葡萄牙文      | `Portuguese_ReliableMan`                    | Reliable Man              |
| 239  | 葡萄牙文      | `Portuguese_SereneElder`                    | Serene Elder              |
| 240  | 葡萄牙文      | `Portuguese_GrimReaper`                     | Grim Reaper               |
| 241  | 葡萄牙文      | `Portuguese_AssertiveQueen`                 | Assertive Queen           |
| 242  | 葡萄牙文      | `Portuguese_WhimsicalGirl`                  | Whimsical Girl            |
| 243  | 葡萄牙文      | `Portuguese_StressedLady`                   | Stressed Lady             |
| 244  | 葡萄牙文      | `Portuguese_FriendlyNeighbor`               | Friendly Neighbor         |
| 245  | 葡萄牙文      | `Portuguese_CaringGirlfriend`               | Caring Girlfriend         |
| 246  | 葡萄牙文      | `Portuguese_PowerfulSoldier`                | Powerful Soldier          |
| 247  | 葡萄牙文      | `Portuguese_FascinatingBoy`                 | Fascinating Boy           |
| 248  | 葡萄牙文      | `Portuguese_RomanticHusband`                | Romantic Husband          |
| 249  | 葡萄牙文      | `Portuguese_StrictBoss`                     | Strict Boss               |
| 250  | 葡萄牙文      | `Portuguese_InspiringLady`                  | Inspiring Lady            |
| 251  | 葡萄牙文      | `Portuguese_PlayfulSpirit`                  | Playful Spirit            |
| 252  | 葡萄牙文      | `Portuguese_ElegantGirl`                    | Elegant Girl              |
| 253  | 葡萄牙文      | `Portuguese_CompellingGirl`                 | Compelling Girl           |
| 254  | 葡萄牙文      | `Portuguese_PowerfulVeteran`                | Powerful Veteran          |
| 255  | 葡萄牙文      | `Portuguese_SensibleManager`                | Sensible Manager          |
| 256  | 葡萄牙文      | `Portuguese_ThoughtfulLady`                 | Thoughtful Lady           |
| 257  | 葡萄牙文      | `Portuguese_TheatricalActor`                | Theatrical Actor          |
| 258  | 葡萄牙文      | `Portuguese_FragileBoy`                     | Fragile Boy               |
| 259  | 葡萄牙文      | `Portuguese_ChattyGirl`                     | Chatty Girl               |
| 260  | 葡萄牙文      | `Portuguese_Conscientiousinstructor`        | Conscientious Instructor  |
| 261  | 葡萄牙文      | `Portuguese_RationalMan`                    | Rational Man              |
| 262  | 葡萄牙文      | `Portuguese_WiseScholar`                    | Wise Scholar              |
| 263  | 葡萄牙文      | `Portuguese_FrankLady`                      | Frank Lady                |
| 264  | 葡萄牙文      | `Portuguese_DeterminedManager`              | Determined Manager        |
| 265  | 法文          | `French_Male_Speech_New`                    | Level-Headed Man          |
| 266  | 法文          | `French_Female_News Anchor`                 | Patient Female Presenter  |
| 267  | 法文          | `French_CasualMan`                          | Casual Man                |
| 268  | 法文          | `French_MovieLeadFemale`                    | Movie Lead Female         |
| 269  | 法文          | `French_FemaleAnchor`                       | Female Anchor             |
| 270  | 法文          | `French_MaleNarrator`                       | Male Narrator             |
| 271  | 印尼文        | `Indonesian_SweetGirl`                      | Sweet Girl                |
| 272  | 印尼文        | `Indonesian_ReservedYoungMan`               | Reserved Young Man        |
| 273  | 印尼文        | `Indonesian_CharmingGirl`                   | Charming Girl             |
| 274  | 印尼文        | `Indonesian_CalmWoman`                      | Calm Woman                |
| 275  | 印尼文        | `Indonesian_ConfidentWoman`                 | Confident Woman           |
| 276  | 印尼文        | `Indonesian_CaringMan`                      | Caring Man                |
| 277  | 印尼文        | `Indonesian_BossyLeader`                    | Bossy Leader              |
| 278  | 印尼文        | `Indonesian_DeterminedBoy`                  | Determined Boy            |
| 279  | 印尼文        | `Indonesian_GentleGirl`                     | Gentle Girl               |
| 280  | 德文          | `German_FriendlyMan`                        | Friendly Man              |
| 281  | 德文          | `German_SweetLady`                          | Sweet Lady                |
| 282  | 德文          | `German_PlayfulMan`                         | Playful Man               |
| 283  | 俄文          | `Russian_HandsomeChildhoodFriend`           | Handsome Childhood Friend |
| 284  | 俄文          | `Russian_BrightHeroine`                     | Bright Queen              |
| 285  | 俄文          | `Russian_AmbitiousWoman`                    | Ambitious Woman           |
| 286  | 俄文          | `Russian_ReliableMan`                       | Reliable Man              |
| 287  | 俄文          | `Russian_CrazyQueen`                        | Crazy Girl                |
| 288  | 俄文          | `Russian_PessimisticGirl`                   | Pessimistic Girl          |
| 289  | 俄文          | `Russian_AttractiveGuy`                     | Attractive Guy            |
| 290  | 俄文          | `Russian_Bad-temperedBoy`                   | Bad-tempered Boy          |
| 291  | 意大利文      | `Italian_BraveHeroine`                      | Brave Heroine             |
| 292  | 意大利文      | `Italian_Narrator`                          | Narrator                  |
| 293  | 意大利文      | `Italian_WanderingSorcerer`                 | Wandering Sorcerer        |
| 294  | 意大利文      | `Italian_DiligentLeader`                    | Diligent Leader           |
| 295  | 阿拉伯文      | `Arabic_CalmWoman`                          | Calm Woman                |
| 296  | 阿拉伯文      | `Arabic_FriendlyGuy`                        | Friendly Guy              |
| 297  | 土耳其文      | `Turkish_CalmWoman`                         | Calm Woman                |
| 298  | 土耳其文      | `Turkish_Trustworthyman`                    | Trustworthy man           |
| 299  | 乌克兰文      | `Ukrainian_CalmWoman`                       | Calm Woman                |
| 300  | 乌克兰文      | `Ukrainian_WiseScholar`                     | Wise Scholar              |
| 301  | 荷兰文        | `Dutch_kindhearted_girl`                    | Kind-hearted girl         |
| 302  | 荷兰文        | `Dutch_bossy_leader`                        | Bossy leader              |
| 303  | 越南文        | `Vietnamese_kindhearted_girl`               | Kind-hearted girl         |
| 304  | 泰文          | `Thai_male_1_sample8`                       | Serene Man                |
| 305  | 泰文          | `Thai_male_2_sample2`                       | Friendly Man              |
| 306  | 泰文          | `Thai_female_1_sample1`                     | Confident Woman           |
| 307  | 泰文          | `Thai_female_2_sample2`                     | Energetic Woman           |
| 308  | 波兰文        | `Polish_male_1_sample4`                     | Male Narrator             |
| 309  | 波兰文        | `Polish_male_2_sample3`                     | Male Anchor               |
| 310  | 波兰文        | `Polish_female_1_sample1`                   | Calm Woman                |
| 311  | 波兰文        | `Polish_female_2_sample3`                   | Casual Woman              |
| 312  | 罗马尼亚文    | `Romanian_male_1_sample2`                   | Reliable Man              |
| 313  | 罗马尼亚文    | `Romanian_male_2_sample1`                   | Energetic Youth           |
| 314  | 罗马尼亚文    | `Romanian_female_1_sample4`                 | Optimistic Youth          |
| 315  | 罗马尼亚文    | `Romanian_female_2_sample1`                 | Gentle Woman              |
| 316  | 希腊文        | `greek_male_1a_v1`                          | Thoughtful Mentor         |
| 317  | 希腊文        | `Greek_female_1_sample1`                    | Gentle Lady               |
| 318  | 希腊文        | `Greek_female_2_sample3`                    | Girl Next Door            |
| 319  | 捷克文        | `czech_male_1_v1`                           | Assured Presenter         |
| 320  | 捷克文        | `czech_female_5_v7`                         | Steadfast Narrator        |
| 321  | 捷克文        | `czech_female_2_v2`                         | Elegant Lady              |
| 322  | 芬兰文        | `finnish_male_3_v1`                         | Upbeat Man                |
| 323  | 芬兰文        | `finnish_male_1_v2`                         | Friendly Boy              |
| 324  | 芬兰文        | `finnish_female_4_v1`                       | Assetive Woman            |
| 325  | 印地文        | `hindi_male_1_v2`                           | Trustworthy Advisor       |
| 326  | 印地文        | `hindi_female_2_v1`                         | Tranquil Woman            |
| 327  | 印地文        | `hindi_female_1_v2`                         | News Anchor               |
