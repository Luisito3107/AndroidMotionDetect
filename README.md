
## 📱 MotionDetect — Physical activity dataset collection via Wear OS device

**MotionDetect** is a dual Android + Wear OS application designed to collect high-frequency sensor data for physical activity recognition. It captures accelerometer and gyroscope sensor data in real time from a smartwatch to generate labeled training datasets for machine learning models.

### 🧠 Purpose

This project aims to facilitate the creation of custom datasets for classifying physical activities such as **standing, walking, running, sitting**, and **lying down**, enabling researchers and developers to train and evaluate their own activity recognition models.

### 🎯 Features

-   📲 Android app to control sessions, manage CSV files, and test trained models (.tflite)
-   ⌚ Wear OS app to collect accelerometer and gyroscope data    
-   🔄 Communication between phone and watch via Play Services Wearable API
-   ⚙️ Configurable start delay, session alias, and packet size    
-   🧪 Real-time model inference using TensorFlow Lite (optional)
-   📁 Export all captured sessions as CSV or ZIP for further analysis
    

### 📊 Data Format
Each session generates a `.csv` file with 100+ samples per second and the following columns:

    timestamp, action, capture_alias, capture_freq, user, user_hand, 
    acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z
