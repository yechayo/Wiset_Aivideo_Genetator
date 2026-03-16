# Seedream 图片生成测试指南

本文档说明如何运行和验证 Seedream 图片生成功能的相关测试。

## 测试文件说明

### 1. SeedreamImageServiceUnitTest.java
**单元测试** - 测试核心提示词生成逻辑，不涉及网络调用

**特点：**
- 快速执行，无需网络连接
- 测试提示词模板的正确性
- 验证不同风格的提示词生成
- 测试边界情况和错误处理

**运行方式：**
```bash
mvn test -Dtest=SeedreamImageServiceUnitTest
```

### 2. SeedreamImageGenerationTest.java
**集成测试** - 测试完整的图片生成流程

**特点：**
- 测试真实的 API 调用
- 需要有效的 Seedream API 配置
- 测试数据库操作
- 测试完整的 HTTP 接口

**运行方式：**
```bash
mvn test -Dtest=SeedreamImageGenerationTest
```

## 测试覆盖范围

### 功能测试

1. **基础服务测试**
   - 服务名称验证
   - 并发槽位检查
   - 基本配置验证

2. **提示词管理测试**
   - 3D 风格九宫格提示词生成
   - REAL 风格九宫格提示词生成
   - ANIME 风格九宫格提示词生成
   - 三视图提示词生成（三种风格）
   - 空值处理测试
   - 负面提示词验证

3. **Grid 模式测试**（新功能）
   - 设置视觉风格（3D/REAL/ANIME）
   - Grid 模式九宫格生成
   - Grid 模式三视图生成
   - Grid 模式一键生成

4. **Multiple 模式测试**（兼容性）
   - Multiple 模式九宫格生成
   - Multiple 模式三视图生成
   - 向后兼容性验证

5. **角色类型测试**
   - 主角完整流程测试
   - 配角跳过表情生成测试
   - 反派角色测试

6. **错误处理测试**
   - 无效视觉风格
   - 无效生成模式
   - 不存在的角色
   - API 调用失败处理

7. **状态查询测试**
   - 生成状态查询
   - 角色详情查询
   - 不同模式的状态返回

8. **重试生成测试**
   - 表情生成重试
   - 三视图生成重试

## 运行前准备

### 1. 配置 Seedream API

在 `application.yml` 或 `application.properties` 中配置：

```yaml
ark:
  base-url: https://ark.cn-beijing.volces.com/api/v3
  api-key: your-api-key-here
  seedream-model: ep-20241205113851-ljzql
```

### 2. 准备测试数据库

确保测试数据库已配置，或者使用 H2 内存数据库：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
    username: sa
    password: password
```

### 3. 创建测试用户

某些测试需要认证，确保数据库中有测试用户：

```bash
# 可以通过注册接口创建
POST /api/auth/register
{
  "username": "testuser",
  "password": "123456",
  "email": "test@example.com"
}
```

## 运行所有测试

```bash
# 运行所有测试
mvn test

# 运行所有图片生成相关测试
mvn test -Dtest=*Image*Test
```

## 测试结果说明

### 成功的测试输出

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.comic.SeedreamImageServiceUnitTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] Running com.comic.SeedreamImageGenerationTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

### 常见问题处理

1. **API 调用失败**
   - 检查 API Key 是否正确
   - 检查网络连接
   - 检查 API 配额是否用尽

2. **数据库连接失败**
   - 检查数据库配置
   - 确保数据库服务正在运行
   - 验证数据库用户权限

3. **认证失败**
   - 确保测试用户已创建
   - 检查 Token 是否有效

## 性能基准

### 预期执行时间

- **单元测试**: < 5 秒
- **集成测试**: 30-60 秒（取决于 API 响应时间）

### API 调用次数

- **单元测试**: 0 次（无网络调用）
- **集成测试**:
  - Grid 模式: 2 次（九宫格 + 三视图）
  - Multiple 模式: 12 次（9个表情 + 3个视图）

## 持续集成配置

在 `.github/workflows/test.yml` 中配置：

```yaml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v2
    - name: Set up JDK 11
      uses: actions/setup-java@v2
      with:
        java-version: '11'
        distribution: 'adopt'
    - name: Run unit tests
      run: mvn test -Dtest=SeedreamImageServiceUnitTest
    - name: Run integration tests
      env:
        ARK_API_KEY: ${{ secrets.ARK_API_KEY }}
      run: mvn test -Dtest=SeedreamImageGenerationTest
```

## 手动测试 API

### 使用 curl 测试

```bash
# 1. 设置视觉风格
curl -X PUT http://localhost:8080/api/characters/CHAR-xxx/visual-style \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"visualStyle": "D_3D"}'

# 2. 生成九宫格（Grid 模式）
curl -X POST "http://localhost:8080/api/characters/CHAR-xxx/generate-expression?mode=grid" \
  -H "Authorization: Bearer YOUR_TOKEN"

# 3. 生成三视图（Grid 模式）
curl -X POST http://localhost:8080/api/characters/CHAR-xxx/generate-three-view \
  -H "Authorization: Bearer YOUR_TOKEN"

# 4. 查询状态
curl http://localhost:8080/api/characters/CHAR-xxx/status \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 使用 Postman 测试

导入以下集合到 Postman：

```json
{
  "info": {
    "name": "Seedream 图片生成测试",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "设置视觉风格",
      "request": {
        "method": "PUT",
        "header": [
          {"key": "Authorization", "value": "Bearer {{token}}"},
          {"key": "Content-Type", "value": "application/json"}
        ],
        "body": {
          "mode": "raw",
          "raw": "{\"visualStyle\": \"D_3D\"}"
        },
        "url": {
          "raw": "{{baseUrl}}/api/characters/{{charId}}/visual-style",
          "host": ["{{baseUrl}}"],
          "path": ["api", "characters", "{{charId}}", "visual-style"]
        }
      }
    }
  ]
}
```

## 测试覆盖率

生成测试覆盖率报告：

```bash
mvn clean test jacoco:report
```

报告位于: `target/site/jacoco/index.html`

## 调试测试

### 启用调试日志

在 `application.yml` 中：

```yaml
logging:
  level:
    com.comic.ai: DEBUG
    com.comic.service: DEBUG
    okhttp3: DEBUG
```

### 单独运行某个测试

```bash
mvn test -Dtest=SeedreamImageServiceUnitTest#test3DExpressionPrompt
```

## 下一步

测试通过后，可以：
1. 部署到生产环境
2. 监控 API 使用情况
3. 收集用户反馈
4. 优化提示词模板
5. 调整图片生成参数