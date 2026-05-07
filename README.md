# Tech-Brain

AI-powered Knowledge Notes and Agent Assistant System.

## Project Overview

Tech-Brain is a Java backend project for intelligent knowledge note management and AI-assisted Q&A.  
It integrates user authentication, note management, AI conversation, local vector embedding, Redis vector storage, and RAG-based knowledge retrieval.

Frontend repository:GitHub:https://github.com/Zeng-Wenjie/tech-brain-web.git | Gitee:https://gitee.com/jewenz/tech-brain-web.git
Deployment: Planned.

## Tech Stack

### Backend
- Java
- Spring Boot
- MyBatis-Plus
- MySQL
- Redis
- JWT
- AOP
- LangChain4j
- Gemini API
- BGE Embedding Model

### Frontend
- Vue
- Element Plus
- Axios

## Features

- User registration and login
- JWT authentication
- User profile management
- Note CRUD
- Pagination query
- Batch delete
- OSS image upload
- AI Q&A
- AI response persistence
- Redis vector storage
- RAG-based knowledge retrieval
- AOP operation logging

## Highlights

- Integrated Gemini API through LangChain4j to provide AI Q&A capability.
- Used a local BGE embedding model to vectorize knowledge content.
- Stored AI responses in both MySQL and Redis vector storage for history tracking and semantic retrieval.
- Implemented JWT-based authentication with interceptors.
- Used AOP to record operation logs in a unified way.

## Project Status

This project is currently used as a Java backend portfolio project.  
Future improvements may include Docker deployment, API documentation, monitoring, and online demo deployment.