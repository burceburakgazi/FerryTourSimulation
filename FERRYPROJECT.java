import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FerryTourSimulation {
    // Constants
    private static final int MAX_CAPACITY = 20;
    private static final int NUM_CARS = 12;
    private static final int NUM_MINIBUSES = 10;
    private static final int NUM_TRUCKS = 8;
    private static final int TOTAL_VEHICLES = NUM_CARS + NUM_MINIBUSES + NUM_TRUCKS;
    
    // Vehicle types
    enum VehicleType {
        CAR(1), MINIBUS(2), TRUCK(3);
        
        private final int capacityUnits;
        
        VehicleType(int capacityUnits) {
            this.capacityUnits = capacityUnits;
        }
        
        public int getCapacityUnits() {
            return capacityUnits;
        }
    }
    
    // Vehicle class
    static class Vehicle {
        private final int id;
        private final VehicleType type;
        private final int capacityUnits;
        private final int startSide;
        private volatile int currentSide;
        private volatile int tollId;
        private volatile int tripsCompleted;
        
        public Vehicle(int id, VehicleType type, int startSide) {
            this.id = id;
            this.type = type;
            this.capacityUnits = type.getCapacityUnits();
            this.startSide = startSide;
            this.currentSide = startSide;
            this.tripsCompleted = 0;
            updateTollId();
        }
        
        private void updateTollId() {
            if (currentSide == 0) {
                tollId = random.nextInt(2);
            } else {
                tollId = 2 + random.nextInt(2);
            }
        }
        
        public synchronized void switchSide() {
            currentSide = 1 - currentSide;
            updateTollId();
        }
        
        // Getters
        public int getId() { return id; }
        public VehicleType getType() { return type; }
        public int getCapacityUnits() { return capacityUnits; }
        public int getStartSide() { return startSide; }
        public int getCurrentSide() { return currentSide; }
        public int getTollId() { return tollId; }
        public int getTripsCompleted() { return tripsCompleted; }
        public synchronized void incrementTrips() { tripsCompleted++; }
    }
    
    // Ferry class
    static class Ferry {
        private volatile int currentSide;
        private volatile int capacityUsed;
        private final List<Vehicle> loadedVehicles;
        private final ReentrantLock ferryLock;
        
        public Ferry(int startSide) {
            this.currentSide = startSide;
            this.capacityUsed = 0;
            this.loadedVehicles = Collections.synchronizedList(new ArrayList<>());
            this.ferryLock = new ReentrantLock();
        }
        
        public boolean canLoad(Vehicle vehicle) {
            ferryLock.lock();
            try {
                return capacityUsed + vehicle.getCapacityUnits() <= MAX_CAPACITY;
            } finally {
                ferryLock.unlock();
            }
        }
        
        public boolean loadVehicle(Vehicle vehicle) {
            ferryLock.lock();
            try {
                if (capacityUsed + vehicle.getCapacityUnits() <= MAX_CAPACITY) {
                    loadedVehicles.add(vehicle);
                    capacityUsed += vehicle.getCapacityUnits();
                    return true;
                }
                return false;
            } finally {
                ferryLock.unlock();
            }
        }
        
        public List<Vehicle> unloadAll() {
            ferryLock.lock();
            try {
                List<Vehicle> unloaded = new ArrayList<>(loadedVehicles);
                loadedVehicles.clear();
                capacityUsed = 0;
                return unloaded;
            } finally {
                ferryLock.unlock();
            }
        }
        
        public void switchSide() {
            currentSide = 1 - currentSide;
        }
        
        // Getters
        public int getCurrentSide() { return currentSide; }
        public int getCapacityUsed() { return capacityUsed; }
        public int getVehicleCount() { 
            ferryLock.lock();
            try {
                return loadedVehicles.size();
            } finally {
                ferryLock.unlock();
            }
        }
    }
    
    // Global variables
    private static final Random random = new Random();
    private static final Ferry ferry = new Ferry(random.nextInt(2));
    private static final AtomicInteger vehiclesAtOriginalSide = new AtomicInteger(0);
    private static final AtomicInteger[] vehiclesInSquare = {new AtomicInteger(0), new AtomicInteger(0)};
    private static volatile boolean simulationComplete = false;
    
    // Synchronization objects
    private static final Semaphore[] tollSemaphores = new Semaphore[4];
    private static final Semaphore[] ferryAvailable = new Semaphore[2];
    private static final Semaphore[] loadingComplete = new Semaphore[2];
    private static final CyclicBarrier[] unloadingBarriers = new CyclicBarrier[2];
    private static volatile CyclicBarrier currentUnloadingBarrier = new CyclicBarrier(1);
    
    private static final ReentrantLock[] tollLocks = new ReentrantLock[4];
    private static final ReentrantLock[] squareLocks = new ReentrantLock[2];
    private static final ReentrantLock[] loadingLocks = new ReentrantLock[2];
    private static final ReentrantLock printLock = new ReentrantLock();
    private static final ReentrantLock ferryOperationLock = new ReentrantLock();
    
    // Vehicle thread class
    static class VehicleThread extends Thread {
        private final Vehicle vehicle;
        
        public VehicleThread(Vehicle vehicle) {
            this.vehicle = vehicle;
        }
        
        @Override
        public void run() {
            try {
                while (vehicle.getTripsCompleted() < 2 && !simulationComplete) {
                    // Pass through toll
                    passToll();
                    
                    // Wait in square
                    waitInSquare();
                    
                    // Board ferry
                    if (boardFerry()) {
                        // Travel and unload
                        travelAndUnload();
                        
                        vehicle.incrementTrips();
                        
                        // Check if vehicle returned to original side
                        if (vehicle.getCurrentSide() == vehicle.getStartSide() && 
                            vehicle.getTripsCompleted() == 2) {
                            int returned = vehiclesAtOriginalSide.incrementAndGet();
                            safePrint("Vehicle %d (%s) returned to original side. Total returned: %d/%d\n",
                                    vehicle.getId(), vehicle.getType(), returned, TOTAL_VEHICLES);
                            
                            if (returned == TOTAL_VEHICLES) {
                                simulationComplete = true;
                            }
                        }
                    }
                    
                    // Short delay before next trip
                    Thread.sleep(50 + random.nextInt(100));
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                safePrint("Error in vehicle %d: %s\n", vehicle.getId(), e.getMessage());
            }
        }
        
        private void passToll() throws InterruptedException {
            int tollId = vehicle.getTollId();
            tollSemaphores[tollId].acquire();
            
            tollLocks[tollId].lock();
            try {
                safePrint("Vehicle %d (%s) paying toll %d on side %d\n",
                        vehicle.getId(), vehicle.getType(), tollId, vehicle.getCurrentSide());
                
                // Simulate toll payment time
                Thread.sleep(30 + random.nextInt(50));
                
            } finally {
                tollLocks[tollId].unlock();
                tollSemaphores[tollId].release();
            }
        }
        
        private void waitInSquare() {
            int side = vehicle.getCurrentSide();
            squareLocks[side].lock();
            try {
                int queueSize = vehiclesInSquare[side].incrementAndGet();
                safePrint("Vehicle %d (%s) waiting in square on side %d. Queue: %d\n",
                        vehicle.getId(), vehicle.getType(), side, queueSize);
            } finally {
                squareLocks[side].unlock();
            }
        }
        
        private boolean boardFerry() throws InterruptedException {
            int side = vehicle.getCurrentSide();
            int attempts = 0;
            final int maxAttempts = 50;
            
            while (attempts < maxAttempts && !simulationComplete) {
                // Try to acquire ferry availability
                if (ferryAvailable[side].tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    loadingLocks[side].lock();
                    try {
                        // Double-check ferry availability and capacity
                        if (ferry.getCurrentSide() == side && ferry.loadVehicle(vehicle)) {
                            safePrint("Vehicle %d (%s) boarded ferry on side %d. Ferry capacity: %d/%d\n",
                                    vehicle.getId(), vehicle.getType(), side, 
                                    ferry.getCapacityUsed(), MAX_CAPACITY);
                            
                            // Remove from square
                            vehiclesInSquare[side].decrementAndGet();
                            
                            // Check if ferry should depart
                            boolean shouldDepart = (ferry.getCapacityUsed() >= MAX_CAPACITY) ||
                                                 (vehiclesInSquare[side].get() == 0) ||
                                                 (ferry.getVehicleCount() >= 5); // Don't wait too long
                            
                            if (shouldDepart) {
                                // Signal ferry to depart
                                loadingComplete[side].release();
                            } else {
                                // Allow next vehicle to board
                                ferryAvailable[side].release();
                            }
                            
                            return true;
                            
                        } else {
                            // Couldn't load, release and try again
                            ferryAvailable[side].release();
                        }
                    } finally {
                        loadingLocks[side].unlock();
                    }
                }
                
                attempts++;
                Thread.sleep(50);
            }
            
            return false; // Failed to board after max attempts
        }
        
        private void travelAndUnload() throws InterruptedException {
            // Wait for ferry to reach destination and unload
            try {
                currentUnloadingBarrier.await(5, TimeUnit.SECONDS);
                vehicle.switchSide();
                safePrint("Vehicle %d (%s) arrived at side %d\n",
                        vehicle.getId(), vehicle.getType(), vehicle.getCurrentSide());
            } catch (TimeoutException e) {
                // If timeout, just switch sides anyway
                vehicle.switchSide();
                safePrint("Vehicle %d (%s) arrived at side %d (timeout)\n",
                        vehicle.getId(), vehicle.getType(), vehicle.getCurrentSide());
            } catch (BrokenBarrierException e) {
                // Barrier was broken, switch sides
                vehicle.switchSide();
            }
        }
    }
    
    // Ferry thread class
    static class FerryThread extends Thread {
        @Override
        public void run() {
            try {
                while (!simulationComplete && vehiclesAtOriginalSide.get() < TOTAL_VEHICLES) {
                    int currentSide = ferry.getCurrentSide();
                    
                    // Wait for vehicles to load or timeout
                    if (loadingComplete[currentSide].tryAcquire(2, TimeUnit.SECONDS)) {
                        ferryOperationLock.lock();
                        try {
                            List<Vehicle> passengers = ferry.unloadAll();
                            
                            if (!passengers.isEmpty()) {
                                safePrint("Ferry departing from side %d with %d vehicles (capacity: %d/%d)\n",
                                        currentSide, passengers.size(),
                                        passengers.stream().mapToInt(Vehicle::getCapacityUnits).sum(), MAX_CAPACITY);
                                
                                // Simulate ferry travel time
                                Thread.sleep(300 + random.nextInt(300));
                                
                                // Arrive at destination
                                ferry.switchSide();
                                safePrint("Ferry arrived at side %d\n", ferry.getCurrentSide());
                                
                                // Create barrier for unloading vehicles
                                currentUnloadingBarrier = new CyclicBarrier(passengers.size() + 1);
                                
                                // Wait for all vehicles to unload
                                currentUnloadingBarrier.await(3, TimeUnit.SECONDS);
                                
                                // Make ferry available for loading on current side
                                ferryAvailable[ferry.getCurrentSide()].release();
                            }
                        } catch (TimeoutException | BrokenBarrierException e) {
                            // Continue if there's an issue with unloading
                            ferryAvailable[ferry.getCurrentSide()].release();
                        } finally {
                            ferryOperationLock.unlock();
                        }
                    } else {
                        // Timeout waiting for loading, check if we should depart anyway
                        if (ferry.getVehicleCount() > 0) {
                            loadingComplete[currentSide].release(); // Force departure
                        } else {
                            // No vehicles, just wait a bit
                            Thread.sleep(200);
                        }
                    }
                }
                
                safePrint("Ferry operations completed. All vehicles returned to original sides.\n");
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    // Utility methods
    private static void safePrint(String format, Object... args) {
        printLock.lock();
        try {
            System.out.printf(format, args);
            System.out.flush();
        } finally {
            printLock.unlock();
        }
    }
    
    public static void main(String[] args) {
        try {
            // Initialize synchronization objects
            for (int i = 0; i < 4; i++) {
                tollSemaphores[i] = new Semaphore(1);
                tollLocks[i] = new ReentrantLock();
            }
            
            for (int i = 0; i < 2; i++) {
                ferryAvailable[i] = new Semaphore(0);
                loadingComplete[i] = new Semaphore(0);
                unloadingBarriers[i] = new CyclicBarrier(1);
                squareLocks[i] = new ReentrantLock();
                loadingLocks[i] = new ReentrantLock();
            }
            
            // Make ferry initially available on its starting side
            ferryAvailable[ferry.getCurrentSide()].release();
            
            safePrint("Ferry starting on side %d\n", ferry.getCurrentSide());
            
            // Create vehicles
            List<Vehicle> vehicles = new ArrayList<>();
            int vehicleId = 0;
            
            // Cars
            for (int i = 0; i < NUM_CARS; i++) {
                int startSide = random.nextInt(2);
                Vehicle car = new Vehicle(vehicleId++, VehicleType.CAR, startSide);
                vehicles.add(car);
                safePrint("Car %d starting on side %d, toll %d\n",
                        car.getId(), car.getStartSide(), car.getTollId());
            }
            
            // Minibuses
            for (int i = 0; i < NUM_MINIBUSES; i++) {
                int startSide = random.nextInt(2);
                Vehicle minibus = new Vehicle(vehicleId++, VehicleType.MINIBUS, startSide);
                vehicles.add(minibus);
                safePrint("Minibus %d starting on side %d, toll %d\n",
                        minibus.getId(), minibus.getStartSide(), minibus.getTollId());
            }
            
            // Trucks
            for (int i = 0; i < NUM_TRUCKS; i++) {
                int startSide = random.nextInt(2);
                Vehicle truck = new Vehicle(vehicleId++, VehicleType.TRUCK, startSide);
                vehicles.add(truck);
                safePrint("Truck %d starting on side %d, toll %d\n",
                        truck.getId(), truck.getStartSide(), truck.getTollId());
            }
            
            // Create and start ferry thread
            FerryThread ferryThread = new FerryThread();
            ferryThread.start();
            
            // Create and start vehicle threads
            List<VehicleThread> vehicleThreads = new ArrayList<>();
            for (Vehicle vehicle : vehicles) {
                VehicleThread vehicleThread = new VehicleThread(vehicle);
                vehicleThreads.add(vehicleThread);
                vehicleThread.start();
            }
            
            // Wait for all vehicle threads to complete
            for (VehicleThread vehicleThread : vehicleThreads) {
                vehicleThread.join(30000); // 30 second timeout per thread
            }
            
            // Signal completion and wait for ferry thread
            simulationComplete = true;
            ferryThread.join(5000); // 5 second timeout
            
            safePrint("Simulation completed successfully!\n");
            safePrint("Final status: %d/%d vehicles returned to original sides.\n", 
                     vehiclesAtOriginalSide.get(), TOTAL_VEHICLES);
            
        } catch (InterruptedException e) {
            System.err.println("Simulation interrupted: " + e.getMessage());
        }
    }
}