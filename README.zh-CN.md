# Tech-Brain 后端项目运行手册（中文）

> 这是一个“照着做就能跑起来”的后端 README。  
> 适合第一次拉取项目、第一次导入数据库、第一次配置环境变量的人使用。

---

## 1. 项目简介

**Tech-Brain** 是一个基于 **Java + Spring Boot + MyBatis-Plus + MySQL + Redis + LangChain4j + Gemini** 的智能知识笔记与 AI 问答系统。

项目核心能力：

- 用户注册、登录、JWT 鉴权
- 用户资料维护、头像上传到阿里云 OSS
- 笔记 CRUD、分页查询、批量删除
- AI 问答接口
- AI 回复保存为笔记
- 本地 BGE Embedding 向量化
- Redis 向量库存储
- RAG 检索增强生成
- AOP 操作日志记录
- Maven 多模块拆分

---

## 2. 技术栈

| 类型 | 技术 |
|---|---|
| 后端语言 | Java 17 |
| 后端框架 | Spring Boot |
| ORM | MyBatis-Plus |
| 数据库 | MySQL 8.x |
| 缓存 / 向量库 | Redis / Redis Stack |
| AI 框架 | LangChain4j |
| 大模型 | Gemini API |
| 向量模型 | BGE Small ZH v1.5 |
| 鉴权 | JWT |
| 文件存储 | 阿里云 OSS |
| 接口文档 | Knife4j / SpringDoc |
| 构建工具 | Maven |
| 日志增强 | AOP |

---

## 3. 项目模块说明

```text
Tech-Brain
├── Tech-Brain-Agent      # 启动模块 + AI/RAG 问答模块
├── Tech-Brain-Notes      # 笔记 CRUD、分页、删除
├── Tech-Bain-Login       # 登录、注册、用户信息
├── Tech-Brain-Common     # 公共工具类、JWT、OSS、上下文等
├── Tech-Brain-AOP        # AOP 操作日志
└── Teach-Brain-Entity    # 实体类、DTO、VO
```

当前项目采用 **单体多模块结构**。  
`Tech-Brain-Agent` 目前承担启动模块作用，同时聚合 Login、Notes、Common、AOP、Entity 等模块。

---

## 4. 环境准备

启动前请先安装这些东西：

| 软件 | 推荐版本 | 用途 |
|---|---|---|
| JDK | 17 | 运行 Spring Boot 项目 |
| Maven | 3.8+ | 构建项目 |
| MySQL | 8.x | 存储用户、笔记、日志 |
| Redis Stack | latest | Redis 缓存 + 向量检索 |
| Git | 任意较新版本 | 拉取代码 |
| IDEA | 2023+ | 开发和启动项目 |

> 注意：普通 Redis 可以做缓存，但如果你要跑 Redis 向量检索，建议使用 **Redis Stack**，因为它包含 RediSearch 等向量检索能力。

---

## 5. 拉取项目

```bash
git clone https://github.com/Zeng-Wenjie/tech-brain-agent.git
cd tech-brain-agent
```

如果你使用 Gitee，也可以替换成自己的 Gitee 仓库地址。

---

## 6. 导入数据库

### 6.1 创建数据库

先登录 MySQL：

```bash
mysql -u root -p
```

创建数据库：

```sql
CREATE DATABASE agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

退出 MySQL：

```sql
exit;
```

---

### 6.2 导入 SQL 文件

建议你把数据库文件放到项目目录：

```text
sql/agent.sql
```

然后执行：

```bash
mysql -u root -p agent < sql/agent.sql
```

如果你的 SQL 文件名还是导出的原始名字，例如：

```text
_localhost-2026_05_07_15_59_16-dump.sql
```

那就执行：

```bash
mysql -u root -p agent < _localhost-2026_05_07_15_59_16-dump.sql
```

---

### 6.3 检查表是否导入成功

进入 MySQL：

```bash
mysql -u root -p
```

执行：

```sql
USE agent;
SHOW TABLES;
```

正常应该能看到：

```text
article
operate_log
user
```

表说明：

| 表名 | 作用 |
|---|---|
| user | 用户账号、密码、头像、昵称、手机号等 |
| article | 用户保存的笔记、AI 回复笔记、标签等 |
| operate_log | AOP 记录的操作日志 |

---

## 7. 启动 Redis Stack

### 方式一：Docker 启动 Redis Stack（推荐）

```bash
docker run -d \
  --name tech-brain-redis \
  -p 6379:6379 \
  -p 8001:8001 \
  redis/redis-stack:latest
```

说明：

| 端口 | 用途 |
|---|---|
| 6379 | 后端连接 Redis |
| 8001 | Redis Stack 可视化管理界面 |

启动后可以检查：

```bash
docker ps
```

测试 Redis 是否能连上：

```bash
docker exec -it tech-brain-redis redis-cli ping
```

如果返回：

```text
PONG
```

说明 Redis 正常。

---

## 8. 配置环境变量

项目里不要直接写数据库密码、Redis 密码、Gemini Key、OSS Key。  
这些信息建议通过环境变量配置。

项目当前需要的环境变量：

| 变量名 | 作用 |
|---|---|
| DB_PASSWORD | MySQL root 用户密码 |
| REDIS_PASSWORD | Redis 密码；如果 Redis 没设置密码，可以留空 |
| GEMINI_API_KEY | Gemini API Key |
| JWT_SECRET_KEY | JWT 签名密钥，建议至少 32 位 |
| OSS_ACCESS_KEY_ID | 阿里云 OSS AccessKey ID |
| OSS_ACCESS_KEY_SECRET | 阿里云 OSS AccessKey Secret |

---

### 8.1 Windows PowerShell 设置方式

只在当前 PowerShell 窗口生效：

```powershell
$env:DB_PASSWORD="你的MySQL密码"
$env:REDIS_PASSWORD=""
$env:GEMINI_API_KEY="你的Gemini API Key"
$env:JWT_SECRET_KEY="your-jwt-secret-key-must-be-long-enough-123456"
$env:OSS_ACCESS_KEY_ID="你的OSS_ACCESS_KEY_ID"
$env:OSS_ACCESS_KEY_SECRET="你的OSS_ACCESS_KEY_SECRET"
```

---

### 8.2 macOS / Linux 设置方式

```bash
export DB_PASSWORD="你的MySQL密码"
export REDIS_PASSWORD=""
export GEMINI_API_KEY="你的Gemini API Key"
export JWT_SECRET_KEY="your-jwt-secret-key-must-be-long-enough-123456"
export OSS_ACCESS_KEY_ID="你的OSS_ACCESS_KEY_ID"
export OSS_ACCESS_KEY_SECRET="你的OSS_ACCESS_KEY_SECRET"
```

---

### 8.3 IDEA 中配置环境变量

如果你用 IDEA 启动项目：

1. 打开 IDEA
2. 找到右上角启动配置
3. 点击 **Edit Configurations**
4. 找到 **Environment variables**
5. 填入：

```text
DB_PASSWORD=你的MySQL密码;REDIS_PASSWORD=;GEMINI_API_KEY=你的Gemini API Key;JWT_SECRET_KEY=your-jwt-secret-key-must-be-long-enough-123456;OSS_ACCESS_KEY_ID=你的OSS_ACCESS_KEY_ID;OSS_ACCESS_KEY_SECRET=你的OSS_ACCESS_KEY_SECRET
```

Windows 下一般用分号 `;` 分隔。  
macOS / Linux 下 IDEA 通常也会自动处理。

---

## 9. 检查配置文件

后端主要配置文件在：

```text
Tech-Brain-Agent/src/main/resources/application.yml
```

核心配置大概长这样：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/agent
    username: root
    password: ${DB_PASSWORD}

  data:
    redis:
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD}
      database: 1

aliyun:
  oss:
    endpoint: https://oss-cn-beijing.aliyuncs.com
    bucketName: your-bucket-name
    region: cn-beijing

gemini:
  api-key: ${GEMINI_API_KEY}
```

如果你的 MySQL 或 Redis 不是本机，请把 `localhost` 改成实际 IP。

---

## 10. 编译项目

在项目根目录执行：

```bash
mvn clean package -DskipTests
```

如果看到类似：

```text
BUILD SUCCESS
```

说明项目编译成功。

---

## 11. 启动后端

### 方式一：IDEA 启动

1. 用 IDEA 打开项目根目录
2. 等 Maven 依赖加载完成
3. 找到 `Tech-Brain-Agent` 模块下的 Spring Boot 启动类
4. 右键运行 `main` 方法

---

### 方式二：Maven 命令启动

在项目根目录执行：

```bash
mvn -pl Tech-Brain-Agent -am spring-boot:run
```

如果启动成功，控制台会看到 Spring Boot 启动日志。

默认访问地址：

```text
http://localhost:8080
```

---

## 12. 接口文档地址

启动后可以尝试访问：

```text
http://localhost:8080/doc.html
```

如果 Knife4j 正常配置，会看到接口文档页面。

也可以尝试 SpringDoc 默认地址：

```text
http://localhost:8080/swagger-ui/index.html
```

---

## 13. 推荐启动顺序

第一次跑项目，严格按这个顺序来：

```text
1. 启动 MySQL
2. 创建 agent 数据库
3. 导入 SQL 文件
4. 启动 Redis Stack
5. 配置环境变量
6. Maven 编译项目
7. 启动 Spring Boot 后端
8. 打开接口文档测试接口
9. 启动前端项目
```

不要跳步骤。  
项目跑不起来，十有八九是数据库、Redis 或环境变量没配好。

---

## 14. 基础接口测试流程

建议用 Apifox / Postman / Knife4j 测试。

### 14.1 注册

```http
POST /register
Content-Type: application/json
```

请求体示例：

```json
{
  "username": "test001",
  "password": "123456"
}
```

---

### 14.2 登录

```http
POST /login
Content-Type: application/json
```

请求体示例：

```json
{
  "username": "test001",
  "password": "123456"
}
```

登录成功后会返回 token。

后续需要登录的接口，请在请求头中携带 token。  
具体请求头名称以你项目拦截器代码为准，常见写法是：

```text
Authorization: Bearer 你的token
```

或者：

```text
token: 你的token
```

---

### 14.3 获取当前用户信息

```http
GET /info
```

需要携带登录 token。

---

### 14.4 更新用户信息

```http
POST /userInformation
Content-Type: application/json
```

请求体示例：

```json
{
  "name": "hugh",
  "email": "test@example.com",
  "phone": "13800000000",
  "age": 26,
  "gender": 1
}
```

---

### 14.5 AI 问答

```http
GET /chat?msg=什么是Spring Boot
```

这个接口会：

```text
1. 把用户问题转成向量
2. 去 Redis 向量库检索相关笔记
3. 拼接 RAG 上下文
4. 调用 Gemini 生成回答
5. 返回 AI 回复
```

---

### 14.6 保存 AI 回复为笔记

```http
POST /save-note
Content-Type: application/json
```

请求体示例：

```json
{
  "title": "Spring Boot 简介",
  "content": "Spring Boot 是一个用于快速构建 Spring 应用的框架。",
  "tags": "Java,Spring Boot"
}
```

保存时会写入：

```text
MySQL article 表
Redis 向量库
```

---

### 14.7 分页查询笔记

```http
GET /article/page?pageNo=1&pageSize=10
```

---

### 14.8 删除笔记

```http
DELETE /article/{id}
```

示例：

```http
DELETE /article/1
```

---

### 14.9 批量删除笔记

```http
DELETE /article/batch
Content-Type: application/json
```

请求体示例：

```json
[1, 2, 3]
```

---

## 15. 前端项目

前端仓库：

```text
GitHub: https://github.com/Zeng-Wenjie/tech-brain-web.git
Gitee:  https://gitee.com/jewenz/tech-brain-web.git
```

前端一般启动方式：

```bash
npm install
npm run dev
```

如果前端请求后端失败，优先检查：

```text
1. 后端是否启动
2. 后端端口是否是 8080
3. 前端 vite.config.js 代理是否配置正确
4. 请求路径是否带 /api
5. 登录后 token 是否正确携带
```

---

## 16. Redis 向量数据说明

MySQL 的 SQL 文件只能导入关系型数据，例如用户、笔记、操作日志。

Redis 向量数据不会随着 MySQL SQL 文件一起导入。

因此第一次启动项目后：

```text
1. 可以先登录
2. 新增或保存一条笔记
3. 项目会把笔记内容转成向量
4. 然后写入 Redis 向量库
5. 后续 /chat 才能检索到更多上下文
```

如果你发现 RAG 没检索到内容，不一定是接口坏了，可能只是 Redis 向量库里还没有数据。

---

## 17. 常见问题排查

### 17.1 MySQL 连接失败

错误类似：

```text
Access denied for user 'root'@'localhost'
```

检查：

```text
1. DB_PASSWORD 是否正确
2. MySQL 是否启动
3. 数据库 agent 是否创建
4. application.yml 里的端口是不是 3306
```

---

### 17.2 找不到环境变量

错误可能类似：

```text
Could not resolve placeholder 'DB_PASSWORD'
```

说明环境变量没配置。

解决：

```text
1. 检查 IDEA Environment variables
2. 或重新打开终端后重新 export / $env
3. 不要只写在普通文本里，必须写进运行环境
```

---

### 17.3 JWT 启动报错

如果 `JWT_SECRET_KEY` 太短，可能导致 JWT 密钥初始化失败。

建议使用至少 32 位字符串：

```text
your-jwt-secret-key-must-be-long-enough-123456
```

---

### 17.4 Redis 连接失败

检查：

```bash
docker ps
docker exec -it tech-brain-redis redis-cli ping
```

如果 Redis 没设置密码，`REDIS_PASSWORD` 可以设置为空。

---

### 17.5 AI 接口没返回

检查：

```text
1. GEMINI_API_KEY 是否正确
2. 当前网络是否能访问 Gemini API
3. 代理是否正常
4. 控制台是否有模型调用异常
```

---

### 17.6 OSS 上传失败

检查：

```text
1. OSS_ACCESS_KEY_ID 是否配置
2. OSS_ACCESS_KEY_SECRET 是否配置
3. bucketName 是否正确
4. endpoint 和 region 是否匹配
5. 阿里云 OSS 权限是否允许上传
```

---

## 18. Git 提交建议

如果你添加了 README 和 SQL 文件，可以这样提交：

```bash
git add README.zh-CN.md README.en-US.md sql/agent.sql
git commit -m "docs: add bilingual setup guide and database initialization"
git push origin main
```

如果只添加 README：

```bash
git add README.zh-CN.md README.en-US.md
git commit -m "docs: add bilingual project setup guide"
git push origin main
```

---

## 19. 项目亮点

这个项目面试可以这样讲：

```text
Tech-Brain 是一个 Java 后端智能知识笔记系统。

项目采用 Maven 多模块结构，将 Entity、Common、Login、Notes、Agent、AOP 拆分，降低模块耦合。

用户登录后可以和 AI 进行技术问答，并把 AI 回复保存为笔记。保存笔记时，系统会同时写入 MySQL 和 Redis 向量库，实现结构化存储和语义检索。

问答时，系统先将用户问题向量化，再从 Redis 向量库检索相关笔记，拼接上下文后调用 Gemini 大模型生成回答，这就是 RAG 检索增强生成流程。

项目还实现了 JWT 鉴权、用户信息维护、OSS 头像上传、分页查询、批量删除、AOP 操作日志、Redis 缓存等后端常见能力。
```

---

## 20. 最后检查清单

启动前最后确认：

```text
[ ] JDK 17 已安装
[ ] Maven 已安装
[ ] MySQL 已启动
[ ] agent 数据库已创建
[ ] SQL 文件已导入
[ ] Redis Stack 已启动
[ ] DB_PASSWORD 已配置
[ ] REDIS_PASSWORD 已配置
[ ] GEMINI_API_KEY 已配置
[ ] JWT_SECRET_KEY 已配置
[ ] OSS_ACCESS_KEY_ID 已配置
[ ] OSS_ACCESS_KEY_SECRET 已配置
[ ] mvn clean package -DskipTests 通过
[ ] Spring Boot 后端启动成功
[ ] /doc.html 可以访问
```

---

## License

This project is currently used as a personal Java backend portfolio project.
