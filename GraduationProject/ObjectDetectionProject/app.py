import argparse
from flask import Flask, request, jsonify
import cv2
from ultralytics import YOLO
import numpy as np

# Create Flask application
app = Flask(__name__)

# Load YOLO model
model = YOLO('yolov8n.pt')

@app.route('/')
def index():
    return 'Flask server is running.'

# Define an API endpoint for processing images and videos using YOLO
@app.route('/predict', methods=['POST', 'GET'])
def predict():
    if request.method == 'GET':
        return jsonify({'status': 'Server is running', 'yolo_model': 'Loaded'})

    try:
        if not request.files:
            return jsonify({'error': 'No file provided'}), 400
        
        file = next((uploaded_file for _, uploaded_file in request.files.items() if uploaded_file.filename), None)
        if file is None:
            return jsonify({'error': 'No file provided'}), 400
        
        filename = file.filename
        file_extension = filename.split('.')[-1].lower()
        
        results_list = []
        
        if file_extension in ['jpg', 'jpeg', 'png']:
            img = cv2.imdecode(np.frombuffer(file.read(), np.uint8), cv2.IMREAD_COLOR)
            results = model(img)
            
            for result in results:
                for box in result.boxes:
                    data = {
                        'class': int(box.cls) if box.cls is not None else None,
                        'confidence': float(box.conf) if box.conf is not None else None,
                        'box': [float(coord) for coord in box.xyxy.flatten().tolist()]
                    }
                    results_list.append(data)
        
        elif file_extension in ['mp4', 'avi']:
            video_path = f'temp_video.{file_extension}'
            file.save(video_path)
            
            cap = cv2.VideoCapture(video_path)
            
            while cap.isOpened():
                ret, frame = cap.read()
                if not ret:
                    break
                
                results = model(frame)
                
                for result in results:
                    for box in result.boxes:
                        data = {
                            'class': int(box.cls) if box.cls is not None else None,
                            'confidence': float(box.conf) if box.conf is not None else None,
                            'box': [float(coord) for coord in box.xyxy.flatten().tolist()]
                        }
                        results_list.append(data)
            
            cap.release()
        
        return jsonify(results_list)
    
    except Exception as e:
        print(f"Error: {e}")
        return jsonify({'error': 'Internal Server Error'}), 500

@app.errorhandler(500)
def internal_server_error(error):
    return jsonify({'error': 'Internal Server Error'}), 500

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Flask app exposing YOLO model")
    parser.add_argument('--port', default=5000, type=int, help='Port number')
    args = parser.parse_args()
    app.run(host='0.0.0.0', port=args.port)