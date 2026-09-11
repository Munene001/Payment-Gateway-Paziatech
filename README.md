# Payment Gateway

<p align="center">
  <strong>Backend payment gateway service built with Spring Boot.</strong>
</p>

<p align="center">
  A backend service for integrating M-Pesa payments into applications through REST APIs.
</p>

<p align="center">
  <a href="https://github.com/Munene001/Payment-Gateway-Paziatech">GitHub Repository</a> ·
  <a href="https://paziatech.co.ke/">PaziaTech</a>
</p>

---

## Overview

Payment Gateway is a Spring Boot backend service designed to simplify M-Pesa payment integration for applications.

The service exposes REST APIs that handle payment requests and communicates with the M-Pesa Daraja API.

## Features

* 💳 M-Pesa payment integration
* 🔌 RESTful APIs
* 🔐 API authentication and validation
* 🔄 Payment request handling
* 📡 External API integration
* ⚠️ Error handling and response management
* 🐳 Docker support

## Tech Stack

| Area             | Technologies      |
| ---------------- | ----------------- |
| Language         | Java              |
| Framework        | Spring Boot       |
| API              | REST              |
| Payment          | M-Pesa Daraja API |
| Build Tool       | Maven             |
| Containerization | Docker            |
| Version Control  | Git / GitHub      |

## Architecture

```text
                  ┌─────────────────┐
                  │    Client App   │
                  └────────┬────────┘
                           │
                           │ REST API
                           ▼
                  ┌─────────────────┐
                  │  Spring Boot    │
                  │ Payment Service │
                  └────────┬────────┘
                           │
                           │ API Request
                           ▼
                  ┌─────────────────┐
                  │  M-Pesa Daraja  │
                  │       API       │
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ Payment Result  │
                  └─────────────────┘
```

## API Flow

```text
Client
  │
  │ Payment Request
  ▼
Spring Boot API
  │
  │ Validate Request
  ▼
Payment Service
  │
  │ Daraja API Request
  ▼
M-Pesa
  │
  │ Response
  ▼
Spring Boot
  │
  ▼
Client
```

## Getting Started

### Requirements

* Java 17+
* Maven
* Docker
* M-Pesa Daraja API credentials

### Clone

```bash
git clone https://github.com/Munene001/Payment-Gateway-Paziatech.git

cd Payment-Gateway-Paziatech
```

### Configure Environment

Configure the required M-Pesa credentials and application settings using environment variables or your local configuration.

Never commit API credentials or other secrets to the repository.

### Run

```bash
./mvnw spring-boot:run
```

Or build the application:

```bash
./mvnw clean package
```

## Docker

Build the image:

```bash
docker build -t payment-gateway .
```

Run the container:

```bash
docker run -p 8080:8080 payment-gateway
```

## What I Worked On

* Built the payment service using Spring Boot and Java.
* Integrated the M-Pesa Daraja API.
* Designed REST endpoints for payment operations.
* Implemented request validation and error handling.
* Structured the application for integration with external applications.
* Containerized the service using Docker.

## Project

**GitHub:**
https://github.com/Munene001/Payment-Gateway-Paziatech

**PaziaTech:**
https://paziatech.co.ke/

---

**Lawrence Munene**
Full-Stack Software Engineer
