# 🚀 확장성 고려 아키텍처 계획서

## 📋 현황 분석

### 문제 상황
```
요청: Java 클라이언트 수십~수백개
      ↓
FastAPI 단일 인스턴스 (YOLO 모델 1개)
      ↓
GPU 메모리 제한 (동시 추론 수 제한)
      ↓
병목 현상 (요청 큐잉, 응답 지연)
```

### 제약조건
- ❌ 딥러닝 모델을 그 수만큼 늘릴 수 없음 (비용, 리소스)
- ✅ Python FastAPI 인스턴스는 늘릴 수 있음 (컨테이너화)
- ✅ Java 클라이언트는 변경 불가 (호환성)

---

## 🎯 해결 방안 3가지

### **방안 1️⃣: FastAPI 비동기 최적화** (FINAL_PLAN.md)

**구현 내용:**
```python
# ThreadPoolExecutor + asyncio
executor = ThreadPoolExecutor(max_workers=4)
result = await asyncio.to_thread(detect_objects, image, quality)
```

**효과:**
| 지표 | 개선 전 | 개선 후 | 개선율 |
|------|--------|--------|--------|
| 동시 처리 | 1개 | 4개 | ⬆️ 4배 |
| 응답 크기 | 680KB | 470KB | ⬇️ 31% |
| 메모리 부하 | 높음 | 낮음 | ⬇️ 30% |

**구현 시간:** ⏱️ **1시간**

**한계:**
- GPU가 1개면 동시 4개 추론 불가능
- 실제 동시성: 1~2개만 가능 (GPU 메모리)

---

### **방안 2️⃣: Python 멀티프로세싱 추가** (추천)

**구현 개념:**
```
요청 1 → 프로세스 풀 → 워커 1 (GPU 사용)
요청 2 → 프로세스 풀 → 워커 2 (다른 GPU 또는 대기)
요청 3 → 프로세스 풀 → 워커 1 (이전 작업 완료 후)
```

**코드:**
```python
from multiprocessing import Pool

# 프로세스 풀 생성 (2~4개 워커)
process_pool = Pool(processes=2)

@app.post("/detect")
async def detect_service(message: str = Form(...), file: UploadFile = File(...)):
    image = Image.open(io.BytesIO(await file.read()))
    
    # 멀티프로세싱으로 YOLO 실행
    loop = asyncio.get_event_loop()
    result_image = await loop.run_in_executor(
        process_pool,
        detect_objects,
        image,
        70
    )
    
    # ... 나머지 코드
```

**효과:**
- 동시 처리: 4개 → 8개 (2배)
- GPU 여러 개 서버: 각 프로세스가 독립적 GPU 사용 가능
- CPU 기반 처리: 완전 병렬화

**구현 시간:** ⏱️ **1시간**

**한계:**
- 여전히 단일 서버 (수평 확장 불가)
- 서버 자원 고정

**권장:** **방안 1 + 2 조합** (총 2시간)
```
FastAPI 최적화 (1시간) + 멀티프로세싱 (1시간) = 2시간
동시 처리: 1개 → 8개 (8배 향상)
```

---

### **방안 3️⃣: Docker Compose 수평 확장** (중장기)

**아키텍처:**
```
Java 클라이언트들
    ↓
Spring Boot (WebClient)
    ↓
Nginx 로드 밸런서 (포트 8000)
    ↓
┌─────────┬─────────┬─────────┐
│ YOLO-1  │ YOLO-2  │ YOLO-3  │
│ :8001   │ :8002   │ :8003   │
└─────────┴─────────┴─────────┘
```

**docker-compose.yml:**
```yaml
version: '3.8'

services:
  # Nginx 로드 밸런서
  nginx:
    image: nginx:latest
    ports:
      - "8000:8000"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
    depends_on:
      - yolo-api-1
      - yolo-api-2
      - yolo-api-3

  # YOLO FastAPI 인스턴스 1
  yolo-api-1:
    build:
      context: ./spring_ai_python
      dockerfile: Dockerfile
    container_name: yolo-api-1
    environment:
      - PYTHONUNBUFFERED=1
    ports:
      - "8001:8000"
    volumes:
      - ./spring_ai_python/yolo26n.pt:/app/yolo26n.pt:ro
      - ./spring_ai_python/results:/app/results

  # YOLO FastAPI 인스턴스 2
  yolo-api-2:
    build:
      context: ./spring_ai_python
      dockerfile: Dockerfile
    container_name: yolo-api-2
    environment:
      - PYTHONUNBUFFERED=1
    ports:
      - "8002:8000"
    volumes:
      - ./spring_ai_python/yolo26n.pt:/app/yolo26n.pt:ro
      - ./spring_ai_python/results:/app/results

  # YOLO FastAPI 인스턴스 3
  yolo-api-3:
    build:
      context: ./spring_ai_python
      dockerfile: Dockerfile
    container_name: yolo-api-3
    environment:
      - PYTHONUNBUFFERED=1
    ports:
      - "8003:8000"
    volumes:
      - ./spring_ai_python/yolo26n.pt:/app/yolo26n.pt:ro
      - ./spring_ai_python/results:/app/results
```

**nginx.conf (로드 밸런싱):**
```nginx
upstream yolo_backend {
    server yolo-api-1:8000 weight=1;
    server yolo-api-2:8000 weight=1;
    server yolo-api-3:8000 weight=1;
}

server {
    listen 8000;
    
    location /detect {
        proxy_pass http://yolo_backend;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
    }
    
    location /results {
        proxy_pass http://yolo_backend;
    }
}
```

**Dockerfile (FastAPI):**
```dockerfile
FROM python:3.11-slim

WORKDIR /app

# 의존성 설치
COPY pyproject.toml .
RUN pip install -e .

# 코드 복사
COPY main.py .
COPY yolo26n.pt .

# 실행
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

**실행:**
```bash
docker-compose up -d --scale yolo-api=3
# 또는
docker-compose up -d  # 위의 3개 서비스 기동
```

**효과:**
- 동시 처리: 8개 → 24개 (3배) 또는 그 이상
- 완전 수평 확장 가능
- 나중에 Kubernetes로 자동 스케일링 가능

**구현 시간:** ⏱️ **2~3시간**

**세부 구성:**
- Dockerfile 작성: 30분
- docker-compose.yml 작성: 30분
- Nginx 설정: 30분
- 테스트 & 디버깅: 1시간

---

## 📊 방안별 비교

| 항목 | 방안 1 | 방안 2 | 방안 1+2 | 방안 3 |
|------|--------|--------|---------|--------|
| **구현 시간** | 1시간 | 1시간 | 2시간 | 2~3시간 |
| **동시 처리** | 4개 | 8개 | 8개 | 24개+ |
| **실제 효과** | 1~2배 | 2~4배 | 4~8배 | 8배+ |
| **비용** | 0원 | 0원 | 0원 | 0원 |
| **난이도** | ⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ |
| **유지보수** | 쉬움 | 중간 | 중간 | 중간 |
| **GPU 활용** | 제한적 | 좋음 | 좋음 | 최고 |
| **수평 확장** | ❌ | ❌ | ❌ | ✅ |

---

## 🎯 **최종 권장안**

### **오늘(1일) 구현:**
```
방안 1 + 방안 2 (2시간)
├─ FINAL_PLAN.md의 FastAPI 최적화
├─ Python multiprocessing 추가
└─ 동시 처리: 4배 → 8배 향상
```

**예상 성과:**
- 동시 처리 능력: 8배 향상
- 응답 속도: 30% 단축
- 메모리 효율: 30% 개선
- **당장 배포 가능** ✅

---

### **내일(2일) 검토:**
```
만약 여전히 병목이라면 → 방안 3 (Docker Compose)
├─ YOLO 컨테이너 3~4개 구성
├─ Nginx 로드 밸런싱
└─ 완전 수평 확장 가능 (나중에 Kubernetes)
```

---

## ⚙️ 구현 순서

### **Phase 1: 오늘 (2시간)**
```
1. FINAL_PLAN.md 구현
   ├─ Java WebClientConfig 수정 (ExchangeStrategies)
   └─ Python main.py 최적화 (ThreadPoolExecutor)
   
2. Python multiprocessing 추가
   ├─ multiprocessing.Pool 초기화
   └─ asyncio.run_in_executor() 적용
```

### **Phase 2: 내일 (필요시, 2~3시간)**
```
1. Docker 컨테이너화
   ├─ Dockerfile 작성
   └─ 로컬 테스트
   
2. docker-compose.yml 작성
   ├─ 3개 YOLO 인스턴스
   └─ Nginx 로드 밸런서
   
3. 통합 테스트
   ├─ Java 클라이언트 다중 요청
   └─ 로드 밸런싱 확인
```

---

## ✅ 예상 질문과 답변

### Q1: GPU 여러 개가 없으면?
**A:** CPU 기반 추론이라면 방안 2만으로도 충분함
- 멀티프로세싱으로 8배 향상 가능
- GPU는 병렬화 불가능하므로 컨테이너 나누는 것이 효과적

### Q2: Docker 복잡하면?
**A:** 방안 1+2만 해도 충분함 (2시간)
- 즉시 4~8배 성능 향상
- Docker는 나중에 필요할 때 추가

### Q3: 하루에 다 하려면?
**A:** 방안 1+2 권장 (총 2시간)
- 점심 먹으면서 기다리고
- 오후에 테스트
- 저녁에 배포 완료

### Q4: Kubernetes는?
**A:** 나중의 이야기
- 먼저 Docker Compose로 3개 인스턴스
- 나중에 필요하면 Docker 이미지 그대로 K8s에 배포

---

## 🚀 **최종 결론**

**하루(2시간)에 가능한 것:**
```
방안 1 + 방안 2 구현
├─ FastAPI 비동기 최적화
├─ Python 멀티프로세싱 추가
└─ 동시 처리: 1개 → 8개 (8배)
```

**결과:**
- ✅ Java 클라이언트 호환성 100% 유지
- ✅ 응답 시간 단축
- ✅ 서버 부하 감소
- ✅ 바로 배포 가능
- ✅ 나중에 Docker로 확장 가능

---

**상세 작성일:** 2026-05-29
**상태:** 아키텍처 설계 완료
