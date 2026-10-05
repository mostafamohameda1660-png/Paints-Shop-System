# Al-Hamd Contracting and Supplies System

A Java-based inventory and management system built with JavaFX for managing stock, purchases, sales, customers, suppliers, rented tools, daily records, and financial reports.

## Overview

This project is a desktop application for managing a trading and contracting business in the paints and supplies sector, including:

- Product, category, and company management
- Batch and inventory tracking
- Purchase, sale, and return recording
- Customer and supplier management
- Cash flow and daily movement tracking
- Monthly, yearly, and daily report generation
- Data storage using SQLite

## Technologies Used

- Java 21
- Maven
- JavaFX
- SQLite JDBC
- Apache POI
- Apache PDFBox
- ICU4J

## Prerequisites

Before running the project, make sure you have:

- Java 21 or later
- Maven 3.8+
- JavaFX available through Maven (downloaded automatically during build)

## Project Structure

```text
.
├── src/
│   └── Main/
│       ├── java/
│       └── resources/
├── data/
├── app/
├── runtime/
├── InstallerFiles/
├── Output/
├── package/
├── pom.xml
├── project_tree.txt
├── README.md
└── target/
```

## Installation and Running

### 1) Clone the project

```bash
git clone <repository-url>
cd System
```

### 2) Build the project

```bash
mvn clean package
```

### 3) Run the application

```bash
mvn javafx:run
```

You can also run the generated JAR file from the `target` folder after packaging if it is available.

## Database

The application initializes its SQLite database automatically when started. It creates the required tables if they are missing and supports some schema and column migrations for future updates.

## Key Features

- Main dashboard showing the overall state of the shop
- Product and inventory management
- Purchase, sale, and return handling
- Cash movement management
- Customer and supplier details management
- Tool management with rental status
- Daily, monthly, and yearly reports
- Report import/export support

## Entry Point

The main application entry point is:

```text
com.shop.Main
```

Maven is configured to launch the application using this main class defined in `pom.xml`.

## Important Notes

- The application is designed as a local desktop system, not a web server.
- Data is stored locally in the project's SQLite database.
- If you plan to run the application in a new environment, ensure Java 21 and Maven are installed and updated.

## License

This project is intended for internal use in the current project and does not include formal warranties unless otherwise specified by the project owner.
