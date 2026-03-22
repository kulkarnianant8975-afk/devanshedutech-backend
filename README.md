# Spring Boot Backend

The Java Spring Boot backend controls authentication, courses, leads, messages, and hiring information, acting as a complete replacement for the Node.js implementation.

## Features
- **Data entities**: User, Course, Lead, Message, Hiring. Extracted from the previous Neon Database schema.
- **DTO validation**: `AuthDTOs`, `CourseDTOs`, `LeadDTOs`, `HiringDTOs`, `MessageDTOs`, and `StatsDTOs` encapsulate API request and response data without exposing raw entities.
- **Auth methods**: Direct Database authentication (BCrypt encoding) and Google OAuth2 integration with Spring Session compatibility.

## Prerequisites
- Java 17+ installed on your system.
- Ensure your Neon Postgres setup remains active (Connection String configuration).
- The frontend `node` proxy handles all cross-origin restrictions (`server.ts` or Vite dev configurations point to `localhost:8080`).

## Environment Variables
Create a run configuration or provide the following env values before executing:
- `DATABASE_URL` (Defaults to your Neon Postgres DB URL string)
- `DATABASE_USERNAME` (Defaults to `neondb_owner`)
- `DATABASE_PASSWORD` 
- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `ALLOWED_ORIGINS` (Optional, defaults to `http://localhost:5173,https://devanshedutech.vercel.app`)

## How to run
You can open this folder (`backend-springboot`) in your favorite Java IDE (IntelliJ IDEA, Eclipse, VS Code).
Run `DevanshEduTechApplication.java` as a standard Java application. 
The application starts natively on `http://localhost:8080`.

Vite is pre-configured to automatically forward `/api` and `/auth` routes to this port seamlessly.
