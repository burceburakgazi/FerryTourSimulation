# Ferry Tour Simulation 🚢

A multithreaded Java simulation of a ferry transport system, developed as part of an Operating Systems course.

Vehicles (cars, minibuses, and trucks) pass through toll booths and are loaded onto a ferry with limited capacity. The ferry transports them between two sides until all vehicles return to their original side.

---

## 🔧 Features

- Each vehicle is implemented as a separate thread
- 4 toll booths (2 on each side)
- Ferry capacity management based on vehicle type:
  - Car = 1 unit
  - Minibus = 2 units
  - Truck = 3 units
- Randomized toll booth and side assignment
- Synchronized loading and unloading processes

---

## 📁 Files Included

- Java source code file (`.java`)

---

## 💻 Requirements

- Java JDK 8 or higher
- Any Java IDE (e.g., IntelliJ, NetBeans) or terminal to compile and run
- Understanding of multithreading and synchronization concepts

---

## 📘 Note

This simulation project was created for academic purposes as part of a university-level Operating Systems course.
