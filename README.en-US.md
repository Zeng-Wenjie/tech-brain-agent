# Tech-Brain Backend Setup Guide (English)

> This README is written as a beginner-friendly, step-by-step guide.  
> Follow it from top to bottom and you should be able to run the backend locally.

---

## 1. Project Overview

**Tech-Brain** is an intelligent knowledge note and AI Q&A backend system built with **Java, Spring Boot, MyBatis-Plus, MySQL, Redis, LangChain4j, and Gemini**.

Core features:

- User registration, login, and JWT authentication
- User profile management
- Avatar upload to Alibaba Cloud OSS
- Note CRUD, pagination, and batch delete
- AI Q&A API
- Save AI responses as notes
- Local BGE embedding generation
- Redis vector storage
- RAG-based knowledge retrieval
- AOP operation logging
- Maven multi-module architecture

---

## 2. Tech Stack

| Category | Technology |
|---|---|
| Language | Java 17 |
| Backend Framework | Spring Boot |
| ORM | MyBatis-Plus |
| Database | MySQL 8.x |
| Cache / Vector Store | Redis / Redis Stack |
| AI Framework | LangChain4j |
| LLM | Gemini API |
| Embedding Model | BGE Small ZH v1.5 |
| Authentication | JWT |
| File Storage | Alibaba Cloud OSS |
| API Documentation | Knife4j / SpringDoc |
| Build Tool | Maven |
| Logging Enhancement | AOP |

---

## 3. Module Structure

```text
Tech-Brain
├── Tech-Brain-Agent      # Application entry module + AI/RAG module
├── Tech-Brain-Notes      # Note CRUD, pagination, delete
├── Tech-Bain-Login       # Login, registration, user profile
├── Tech-Brain-Common     # Common utilities, JWT, OSS, user context
├── Tech-Brain-AOP        # AOP operation logging
└── Teach-Brain-Entity    # Entities, DTOs, VOs
```

This project currently uses a **modular monolith** structure.  
`Tech-Brain-Agent` works as the main startup module and aggregates Login, Notes, Common, AOP, and Entity modules.

---

## 4. Prerequisites

Install the following tools first:

| Tool | Recommended Version | Purpose |
|---|---|---|
| JDK | 17 | Run the Spring Boot backend |
| Maven | 3.8+ | Build the project |
| MySQL | 8.x | Store users, notes, and operation logs |
| Redis Stack | latest | Redis cache and vector search |
| Git | Recent version | Clone the repository |
| IntelliJ IDEA | 2023+ | Development and local startup |

> Note: Regular Redis can be used for caching, but Redis vector search requires **Redis Stack**, because it includes RediSearch and vector indexing capabilities.

---

## 5. Clone the Repository

```bash
git clone https://github.com/Zeng-Wenjie/tech-brain-agent.git
cd tech-brain-agent
```

If you use Gitee, replace the URL with your Gitee repository URL.

---

## 6. Import the Database

### 6.1 Create the Database

Log in to MySQL:

```bash
mysql -u root -p
```

Create the database:

```sql
CREATE DATABASE agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

Exit MySQL:

```sql
exit;
```

---

### 6.2 Import the SQL File

It is recommended to place the SQL file here:

```text
sql/agent.sql
```

Then run:

```bash
mysql -u root -p agent < sql/agent.sql
```

If your SQL file still uses the original exported name, for example:

```text
_localhost-2026_05_07_15_59_16-dump.sql
```

run:

```bash
mysql -u root -p agent < _localhost-2026_05_07_15_59_16-dump.sql
```

---

### 6.3 Verify the Imported Tables

Log in to MySQL:

```bash
mysql -u root -p
```

Run:

```sql
USE agent;
SHOW TABLES;
```

You should see:

```text
article
operate_log
user
```

Table description:

| Table | Description |
|---|---|
| user | Stores user accounts, passwords, avatars, nicknames, phone numbers, etc. |
| article | Stores user notes, AI-generated notes, tags, and content |
| operate_log | Stores AOP operation logs |

---

## 7. Start Redis Stack

### Option 1: Start Redis Stack with Docker (Recommended)

```bash
docker run -d \
  --name tech-brain-redis \
  -p 6379:6379 \
  -p 8001:8001 \
  redis/redis-stack:latest
```

Port description:

| Port | Purpose |
|---|---|
| 6379 | Backend Redis connection |
| 8001 | Redis Stack web console |

Check whether the container is running:

```bash
docker ps
```

Test Redis:

```bash
docker exec -it tech-brain-redis redis-cli ping
```

If you see:

```text
PONG
```

Redis is running correctly.

---

## 8. Configure Environment Variables

Do not hard-code database passwords, Redis passwords, Gemini keys, or OSS keys in the source code.  
Use environment variables instead.

Required environment variables:

| Variable | Description |
|---|---|
| DB_PASSWORD | MySQL root password |
| REDIS_PASSWORD | Redis password. Leave it empty if Redis has no password |
| GEMINI_API_KEY | Gemini API key |
| JWT_SECRET_KEY | JWT signing secret. At least 32 characters is recommended |
| OSS_ACCESS_KEY_ID | Alibaba Cloud OSS AccessKey ID |
| OSS_ACCESS_KEY_SECRET | Alibaba Cloud OSS AccessKey Secret |

---

### 8.1 Windows PowerShell

Effective only in the current PowerShell window:

```powershell
$env:DB_PASSWORD="your_mysql_password"
$env:REDIS_PASSWORD=""
$env:GEMINI_API_KEY="your_gemini_api_key"
$env:JWT_SECRET_KEY="your-jwt-secret-key-must-be-long-enough-123456"
$env:OSS_ACCESS_KEY_ID="your_oss_access_key_id"
$env:OSS_ACCESS_KEY_SECRET="your_oss_access_key_secret"
```

---

### 8.2 macOS / Linux

```bash
export DB_PASSWORD="your_mysql_password"
export REDIS_PASSWORD=""
export GEMINI_API_KEY="your_gemini_api_key"
export JWT_SECRET_KEY="your-jwt-secret-key-must-be-long-enough-123456"
export OSS_ACCESS_KEY_ID="your_oss_access_key_id"
export OSS_ACCESS_KEY_SECRET="your_oss_access_key_secret"
```

---

### 8.3 IntelliJ IDEA Environment Variables

If you start the backend from IntelliJ IDEA:

1. Open IntelliJ IDEA
2. Open the Run Configuration in the top-right corner
3. Click **Edit Configurations**
4. Find **Environment variables**
5. Add:

```text
DB_PASSWORD=your_mysql_password;REDIS_PASSWORD=;GEMINI_API_KEY=your_gemini_api_key;JWT_SECRET_KEY=your-jwt-secret-key-must-be-long-enough-123456;OSS_ACCESS_KEY_ID=your_oss_access_key_id;OSS_ACCESS_KEY_SECRET=your_oss_access_key_secret
```

On Windows, variables are usually separated by semicolons `;`.

---

## 9. Check the Application Configuration

The main backend configuration file is located at:

```text
Tech-Brain-Agent/src/main/resources/application.yml
```

The important parts look like this:

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

If your MySQL or Redis is not running on your local machine, replace `localhost` with the actual host address.

---

## 10. Build the Project

Run this command in the project root directory:

```bash
mvn clean package -DskipTests
```

If you see:

```text
BUILD SUCCESS
```

the project has been built successfully.

---

## 11. Start the Backend

### Option 1: Start from IntelliJ IDEA

1. Open the project root directory in IntelliJ IDEA
2. Wait until Maven dependencies are loaded
3. Find the Spring Boot main class under the `Tech-Brain-Agent` module
4. Right-click the `main` method and run it

---

### Option 2: Start with Maven

Run this command in the project root directory:

```bash
mvn -pl Tech-Brain-Agent -am spring-boot:run
```

If the startup succeeds, you will see Spring Boot logs in the console.

Default backend address:

```text
http://localhost:8080
```

---

## 12. API Documentation

After the backend starts, try opening:

```text
http://localhost:8080/doc.html
```

If Knife4j is configured correctly, the API documentation page will be displayed.

You can also try the SpringDoc default URL:

```text
http://localhost:8080/swagger-ui/index.html
```

---

## 13. Recommended Startup Order

For the first local run, follow this order strictly:

```text
1. Start MySQL
2. Create the agent database
3. Import the SQL file
4. Start Redis Stack
5. Configure environment variables
6. Build the Maven project
7. Start the Spring Boot backend
8. Open the API documentation page
9. Start the frontend project
```

Do not skip steps.  
Most startup failures come from incorrect database, Redis, or environment variable configuration.

---

## 14. Basic API Test Flow

Use Apifox, Postman, or Knife4j to test the APIs.

### 14.1 Register

```http
POST /register
Content-Type: application/json
```

Example request body:

```json
{
  "username": "test001",
  "password": "123456"
}
```

---

### 14.2 Login

```http
POST /login
Content-Type: application/json
```

Example request body:

```json
{
  "username": "test001",
  "password": "123456"
}
```

After a successful login, the backend returns a token.

For protected APIs, include the token in the request headers.  
The exact header name depends on your interceptor implementation. Common examples:

```text
Authorization: Bearer your_token
```

or:

```text
token: your_token
```

---

### 14.3 Get Current User Info

```http
GET /info
```

A login token is required.

---

### 14.4 Update User Info

```http
POST /userInformation
Content-Type: application/json
```

Example request body:

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

### 14.5 AI Q&A

```http
GET /chat?msg=What is Spring Boot
```

This API will:

```text
1. Convert the user question into an embedding
2. Search related notes from Redis vector storage
3. Build the RAG context
4. Call Gemini to generate an answer
5. Return the AI response
```

---

### 14.6 Save an AI Response as a Note

```http
POST /save-note
Content-Type: application/json
```

Example request body:

```json
{
  "title": "Spring Boot Introduction",
  "content": "Spring Boot is a framework for quickly building Spring applications.",
  "tags": "Java,Spring Boot"
}
```

Saving a note writes data into:

```text
MySQL article table
Redis vector storage
```

---

### 14.7 Query Notes with Pagination

```http
GET /article/page?pageNo=1&pageSize=10
```

---

### 14.8 Delete a Note

```http
DELETE /article/{id}
```

Example:

```http
DELETE /article/1
```

---

### 14.9 Batch Delete Notes

```http
DELETE /article/batch
Content-Type: application/json
```

Example request body:

```json
[1, 2, 3]
```

---

## 15. Frontend Project

Frontend repository:

```text
GitHub: https://github.com/Zeng-Wenjie/tech-brain-web.git
Gitee:  https://gitee.com/jewenz/tech-brain-web.git
```

Typical frontend startup commands:

```bash
npm install
npm run dev
```

If frontend requests fail, check:

```text
1. Is the backend running?
2. Is the backend port 8080?
3. Is the proxy in vite.config.js configured correctly?
4. Does the request path use /api?
5. Is the login token included correctly?
```

---

## 16. Redis Vector Data Notes

The MySQL SQL file only imports relational data, such as users, notes, and operation logs.

Redis vector data is not included in the MySQL dump.

After the first startup:

```text
1. Log in
2. Create or save a note
3. The project will convert the note content into an embedding
4. The embedding will be stored in Redis vector storage
5. Later /chat requests can retrieve more context from Redis
```

If RAG returns little or no context, the API may not be broken.  
The Redis vector store may simply be empty.

---

## 17. Troubleshooting

### 17.1 MySQL Connection Failed

Possible error:

```text
Access denied for user 'root'@'localhost'
```

Check:

```text
1. Is DB_PASSWORD correct?
2. Is MySQL running?
3. Has the agent database been created?
4. Is the MySQL port 3306?
```

---

### 17.2 Environment Variable Not Found

Possible error:

```text
Could not resolve placeholder 'DB_PASSWORD'
```

This means the environment variable is not configured.

Fix:

```text
1. Check IntelliJ IDEA Environment variables
2. Or reopen the terminal and run export / $env again
3. Environment variables must exist in the runtime environment
```

---

### 17.3 JWT Startup Error

If `JWT_SECRET_KEY` is too short, JWT key initialization may fail.

Use a string with at least 32 characters:

```text
your-jwt-secret-key-must-be-long-enough-123456
```

---

### 17.4 Redis Connection Failed

Check:

```bash
docker ps
docker exec -it tech-brain-redis redis-cli ping
```

If Redis has no password, set `REDIS_PASSWORD` to an empty value.

---

### 17.5 AI API Does Not Respond

Check:

```text
1. Is GEMINI_API_KEY correct?
2. Can your network access the Gemini API?
3. Is your proxy working if needed?
4. Does the console show model invocation errors?
```

---

### 17.6 OSS Upload Failed

Check:

```text
1. Is OSS_ACCESS_KEY_ID configured?
2. Is OSS_ACCESS_KEY_SECRET configured?
3. Is bucketName correct?
4. Do endpoint and region match?
5. Does the OSS account have upload permission?
```

---

## 18. Suggested Git Commit

If you add both README files and the SQL file:

```bash
git add README.zh-CN.md README.en-US.md sql/agent.sql
git commit -m "docs: add bilingual setup guide and database initialization"
git push origin main
```

If you only add the README files:

```bash
git add README.zh-CN.md README.en-US.md
git commit -m "docs: add bilingual project setup guide"
git push origin main
```

---

## 19. Project Highlights for Interviews

You can introduce the project like this:

```text
Tech-Brain is a Java backend intelligent knowledge note system.

It uses a Maven multi-module architecture to separate Entity, Common, Login, Notes, Agent, and AOP modules, reducing coupling between modules.

After logging in, users can ask technical questions to the AI assistant and save AI responses as notes. When a note is saved, the system writes it into both MySQL and Redis vector storage, enabling both structured storage and semantic retrieval.

During Q&A, the system first converts the user question into an embedding, searches related notes from Redis vector storage, builds a RAG context, and then calls Gemini to generate the final response.

The project also includes JWT authentication, user profile management, OSS avatar upload, pagination, batch delete, AOP operation logging, and Redis caching.
```

---

## 20. Final Checklist

Before starting the backend, confirm:

```text
[ ] JDK 17 is installed
[ ] Maven is installed
[ ] MySQL is running
[ ] The agent database has been created
[ ] The SQL file has been imported
[ ] Redis Stack is running
[ ] DB_PASSWORD is configured
[ ] REDIS_PASSWORD is configured
[ ] GEMINI_API_KEY is configured
[ ] JWT_SECRET_KEY is configured
[ ] OSS_ACCESS_KEY_ID is configured
[ ] OSS_ACCESS_KEY_SECRET is configured
[ ] mvn clean package -DskipTests succeeds
[ ] Spring Boot backend starts successfully
[ ] /doc.html is accessible
```

---

## License

This project is currently used as a personal Java backend portfolio project.
