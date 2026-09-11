# Payment Gateway

<p align="center">
  <strong>Payment gateway service built with Spring Boot.</strong>
</p>

<p align="center">
  A backend service integrating M-Pesa Daraja and Kopo Kopo payment APIs through REST endpoints.
</p>

<p align="center">
  <a href="https://github.com/Munene001/Payment-Gateway-Paziatech">GitHub Repository</a> ·
  <a href="https://paziatech.co.ke/">PaziaTech</a>
</p>

---

## Overview

Payment Gateway is a Spring Boot backend service for integrating mobile money payments into applications.

The service works with both the **M-Pesa Daraja API** and **Kopo Kopo API**, providing a backend layer for handling payment requests and communicating with external payment providers.

## Features

* 💳 M-Pesa Daraja API integration
* 💰 Kopo Kopo API integration
* 🔌 RESTful APIs
* 🔐 Request validation and authentication
* 🔄 Payment request handling
* 📡 Third-party API integration
* ⚠️ Error handling
* 🐳 Docker support

## Tech Stack

| Area             | Technologies             |
| ---------------- | ------------------------ |
| Language         | Java                     |
| Framework        | Spring Boot              |
| API              | REST                     |
| Payment APIs     | M-Pesa Daraja, Kopo Kopo |
| Build Tool       | Maven                    |
| Containerization | Docker                   |
| Version Control  | Git / GitHub             |

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
                    ┌───────────┴───────────┐
                    │                       │
                    ▼                       ▼
             ┌──────────────┐       ┌──────────────┐
             │ M-Pesa       │       │ Kopo Kopo    │
             │ Daraja API   │       │ API          │
             └──────────────┘       └──────────────┘
```

## Payment Flow

```text
Client
  │
  │ Payment Request
  ▼
Spring Boot API
  │
  ├── M-Pesa Daraja
  │
  └── Kopo Kopo
        │
        ▼
   Payment Provider
        │
        ▼
   Payment Response
        │
        ▼
   Spring Boot API
        │
        ▼
      Client
```

## Getting Started

### Requirements

* Java 17+
* Maven
* Docker
* M-Pesa Daraja credentials
* Kopo Kopo API credentials

### Clone

```bash
git clone https://github.com/Munene001/Payment-Gateway-Paziatech.git

cd Payment-Gateway-Paziatech
```

### Configuration

Configure the required payment provider credentials and application settings using environment variables or your local configuration.

Do not commit API credentials or other secrets to the repository.

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

* Built the payment service using Java and Spring Boot.
* Integrated M-Pesa Daraja and Kopo Kopo APIs.
* Developed REST endpoints for payment operations.
* Implemented request validation and error handling.
* Worked with external payment APIs and their responses.
* Structured the service for integration with other applications.
* Containerized the application using Docker.

## Project

**GitHub:**
https://github.com/Munene001/Payment-Gateway-Paziatech

**PaziaTech:**
https://paziatech.co.ke/

---

**Lawrence Munene**
Full-Stack Software Engineer
