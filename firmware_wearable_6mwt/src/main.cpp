#include <Arduino.h>
#include <Adafruit_LSM9DS1.h>
#include <Adafruit_Sensor.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- Configuración del Sensor---
Adafruit_LSM9DS1 lsm = Adafruit_LSM9DS1();

// --- Lógica de Detección de Pasos ---
int stepCount = 0;
const float ACCEL_THRESHOLD_HIGH = 12.0; 
const float ACCEL_THRESHOLD_LOW = 9.5;
bool highPeakDetected = false;
unsigned long lastStepTime = 0;
const int DEBOUNCE_TIME_MS = 350;

// --- Configuración del Servidor BLE ---
BLEServer* pServer = NULL;
BLECharacteristic* pDistanceCharacteristic = NULL;
bool deviceConnected = false;

// UUIDs únicos para el servicio y la característica.
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define STEPS_CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"


// Clase para manejar los callbacks de conexión y desconexión del servidor BLE
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println("Dispositivo conectado");
    }

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println("Dispositivo desconectado, reiniciando advertising...");
      pServer->getAdvertising()->start(); // Reiniciar el "anuncio" para que se pueda volver a encontrar
    }
};

void setup() {
  Serial.begin(115200);
  
  // Esperar un máximo de 3 segundos para que se conecte el puerto serie.
  // Si no se conecta, el programa continúa igualmente.
  unsigned long start_time = millis();
  while (!Serial && (millis() - start_time) < 3000) {
    delay(10);
  }

  Serial.println("Iniciando Wearable 6MWT v1.0");

  // Inicialización del sensor
  if (!lsm.begin()) {
    Serial.println("Error: No se pudo encontrar el sensor LSM9DS1.");
    while (1) { delay(10); }
  }
  Serial.println("Sensor LSM9DS1 encontrado.");
  
  lsm.setupAccel(lsm.LSM9DS1_ACCELRANGE_2G);
  lsm.setupMag(lsm.LSM9DS1_MAGGAIN_4GAUSS);
  lsm.setupGyro(lsm.LSM9DS1_GYROSCALE_245DPS);

  // ---Inicialización del BLE ---
  Serial.println("Inicializando BLE...");
  
  // 1. Inicializar el dispositivo BLE y ponerle un nombre
  BLEDevice::init("WearableDistancia6MWT");

  // 2. Crear el servidor BLE
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks()); // Asignar callbacks

  // 3. Crear el servicio, usando el UUID definido
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // 4. Crear la característica para los pasos
  pDistanceCharacteristic = pService->createCharacteristic(
                      STEPS_CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_READ |
                      BLECharacteristic::PROPERTY_NOTIFY // Propiedades: se puede leer y notificar cambios
                    );

  pDistanceCharacteristic->addDescriptor(new BLE2902()); // Descriptor estándar necesario para las notificaciones

  // 5. Iniciar el servicio
  pService->start();

  // 6. Empezar a "anunciar" (advertising) el servicio para que la tablet lo pueda encontrar
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  
  Serial.println("Servicio BLE iniciado. Esperando conexión...");
}

void loop() {
  lsm.read(); 
  sensors_event_t accel, mag, gyro, temp;
  lsm.getEvent(&accel, &mag, &gyro, &temp); 
  
  float ax = accel.acceleration.x;
  float ay = accel.acceleration.y;
  float az = accel.acceleration.z;
  float magnitude = sqrt(ax * ax + ay * ay + az * az);
  
  unsigned long currentTime = millis();
  
  if (magnitude > ACCEL_THRESHOLD_HIGH && !highPeakDetected) {
    if (currentTime - lastStepTime > DEBOUNCE_TIME_MS) {
        highPeakDetected = true; 
    }
  }
  
  if (highPeakDetected && magnitude < ACCEL_THRESHOLD_LOW) {
    // Si detecta un paso, incrementa el contador Y la distancia
    stepCount++;
    lastStepTime = currentTime;
    
    // Imprimir por el monitor serie para depurar
    Serial.print("¡PASO! Total: ");
    Serial.println(stepCount);

    highPeakDetected = false; 

    // --- LÓGICA DE NOTIFICACIÓN ---
    if (deviceConnected) {
      // Convertir el entero 'stepCount' a un array de 4 bytes
      // El formato "Little Endian" es el estándar en BLE
      uint8_t data_to_send[4];
      data_to_send[0] = (stepCount >> 0) & 0xFF;
      data_to_send[1] = (stepCount >> 8) & 0xFF;
      data_to_send[2] = (stepCount >> 16) & 0xFF;
      data_to_send[3] = (stepCount >> 24) & 0xFF;
      
      // Enviar los 4 bytes del entero
      pDistanceCharacteristic->setValue(data_to_send, 4);
      pDistanceCharacteristic->notify();
      Serial.println("  -> Notificación BLE enviada (pasos).");
    }
  }
  
  delay(20);
}
