# 파이썬 백엔드 수정 계획서

## 📋 개요
YOLO 객체 탐지 API의 파이썬 백엔드를 다음과 같이 수정합니다:
- ThreadPoolExecutor를 이용한 모델 추론 비동기 처리
- URL 또는 Base64 형식 선택 가능
- 응답 형식에 따른 이미지 품질 조정 (URL: 90%, Base64: 70%)
- UUID 기반 파일명 생성

---

## 🔧 수정 사항

### 1. 임포트 추가
```python
from concurrent.futures import ThreadPoolExecutor
from fastapi.staticfiles import StaticFiles
from fastapi import Query
import uuid
import os
import asyncio
```

### 2. 초기 설정
```python
# ThreadPoolExecutor 초기화
executor = ThreadPoolExecutor(max_workers=4)

# results 디렉토리 생성
os.makedirs("results", exist_ok=True)

# 정적 파일 서빙
app.mount("/results", StaticFiles(directory="results"), name="results")
```

### 3. 응답 모델 수정
```python
class DetectionResult(BaseModel):
    message: str
    image: str  # Base64 문자열 또는 URL
    format: str  # "base64" 또는 "url"
```

### 4. detect_objects 함수 수정
**변경 전:**
```python
def detect_objects(image: Image):
    # ... 기존 코드 ...
    result_image = Image.fromarray(img)
    return result_image
```

**변경 후:**
```python
def detect_objects(image: Image, quality: int = 90):
    img = np.array(image)
    results = model(img)
    class_names = model.names

    for result in results:
        boxes = result.boxes.xyxy
        confidence = result.boxes.conf
        class_ids = result.boxes.cls
        for box, confidence, class_id in zip(boxes, confidence, class_ids):
            x1, y1, x2, y2 = map(int, box)
            label = class_names[int(class_id)]
            cv2.rectangle(img, (x1, y1), (x2, y2), (255, 0, 0), 2)
            cv2.putText(img, f"{label} {confidence:.2f}", (x1, y1),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 0, 0), 2)
    
    result_image = Image.fromarray(img)
    return result_image, quality  # quality 반환
```

### 5. API 엔드포인트 수정
**변경 전:**
```python
@app.post("/detect", response_model=DetectionResult)
async def detect_service(message: str = Form(...), file: UploadFile = File(...)):
    image = Image.open(io.BytesIO(await file.read()))
    if image.mode == "RGBA":
        image = image.convert("RGB")
    elif image.mode != "RGB":
        image = image.convert("RGB")

    result_image = detect_objects(image)
    buffered = io.BytesIO()
    result_image.save(buffered, format="JPEG")
    img_str = base64.b64encode(buffered.getvalue()).decode("utf-8")

    return DetectionResult(message=message, image=img_str)
```

**변경 후:**
```python
@app.post("/detect", response_model=DetectionResult)
async def detect_service(
    message: str = Form(...), 
    file: UploadFile = File(...),
    format: str = Query("base64", regex="^(url|base64)$")
):
    # 이미지 전처리
    image = Image.open(io.BytesIO(await file.read()))
    if image.mode == "RGBA":
        image = image.convert("RGB")
    elif image.mode != "RGB":
        image = image.convert("RGB")

    # 품질 설정
    quality = 90 if format == "url" else 70

    # ThreadPoolExecutor를 통해 모델 추론 실행
    result_image = await asyncio.to_thread(detect_objects, image, quality)

    # 응답 형식 분기
    if format == "url":
        # URL 형식: 파일 저장 후 URL 반환
        filename = f"result_{uuid.uuid4()}.jpg"
        filepath = os.path.join("results", filename)
        result_image.save(filepath, format="JPEG", quality=quality)
        image_url = f"http://127.0.0.1:8000/results/{filename}"
        return DetectionResult(message=message, image=image_url, format="url")
    else:
        # Base64 형식: 메모리에서 Base64 인코딩
        buffered = io.BytesIO()
        result_image.save(buffered, format="JPEG", quality=quality)
        img_str = base64.b64encode(buffered.getvalue()).decode("utf-8")
        return DetectionResult(message=message, image=img_str, format="base64")
```

### 6. 엔드포인트 추가 (선택사항)
```python
# 결과 이미지 삭제 엔드포인트 (선택사항)
@app.delete("/results/{filename}")
async def delete_result(filename: str):
    filepath = os.path.join("results", filename)
    if os.path.exists(filepath):
        os.remove(filepath)
        return {"message": f"{filename} deleted"}
    return {"error": "File not found"}
```

---

## 🔄 동작 흐름

### Base64 요청 (70% 품질)
```
POST /detect?format=base64
- 이미지 업로드
- ThreadPoolExecutor에서 모델 추론 실행
- 메모리에서 70% 품질로 JPEG 인코딩
- Base64 문자열 반환
- 응답: {"message": "...", "image": "base64_문자열...", "format": "base64"}
```

### URL 요청 (90% 품질)
```
POST /detect?format=url
- 이미지 업로드
- ThreadPoolExecutor에서 모델 추론 실행
- 90% 품질로 results/ 디렉토리에 저장
- UUID 기반 파일명 생성
- URL 반환
- 응답: {"message": "...", "image": "http://127.0.0.1:8000/results/result_uuid.jpg", "format": "url"}
```

---

## ✅ 필수 구현 확인 사항

- [ ] ThreadPoolExecutor max_workers=4 설정
- [ ] asyncio.to_thread() 사용
- [ ] format 파라미터 Query 추가
- [ ] UUID 기반 파일명 생성
- [ ] 품질: URL(90%), Base64(70%)
- [ ] StaticFiles 미들웨어로 /results 서빙
- [ ] 응답 모델에 format 필드 추가
- [ ] results/ 디렉토리 자동 생성

---

## 📦 의존성
현재 설치된 패키지로 충분:
- fastapi
- ultralytics (YOLO)
- pillow (PIL)
- numpy
- opencv-python (cv2)
- uvicorn

---

## 🚀 테스트 시나리오

### 1. Base64 테스트
```bash
curl -X POST "http://127.0.0.1:8000/detect?format=base64" \
  -F "message=test" \
  -F "file=@sample.jpg"
```

### 2. URL 테스트
```bash
curl -X POST "http://127.0.0.1:8000/detect?format=url" \
  -F "message=test" \
  -F "file=@sample.jpg"
```

### 3. 결과 확인
- Base64: 응답의 image 값이 `data:image/jpeg;base64,...`로 시작
- URL: 응답의 image 값이 `http://...`로 시작

---

## 📝 파일 구조
```
spring_ai_python/
├── main.py (수정)
├── test.py
├── yolo26n.pt
├── pyproject.toml
├── results/  (자동 생성)
└── PYTHON_MODIFICATION_PLAN.md
```

---

## 🔗 연동 정보
- **서버 주소**: http://127.0.0.1:8000
- **API 엔드포인트**: POST /detect
- **쿼리 파라미터**: ?format=base64|url
- **정적 파일 경로**: /results
