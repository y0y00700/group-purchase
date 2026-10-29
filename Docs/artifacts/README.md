# 설계 산출물

현재 ERD·SA·인프라 도식은 작성 대기다. 실제 산출물을 만들 때 필요한 디렉터리를 생성한다.

<a id="erd"></a>

## ERD (`erd/`)

서비스별 소유 데이터의 논리 모델부터 작성하고 물리 테이블·컬럼·제약은 구현 설계와 연결한다. 서비스 간 식별자 참조와 같은 DB의 FK를 구분한다. 근거는 [데이터와 트랜잭션](../design/payment-and-messaging.md#section-10)이다.

<a id="architecture"></a>

## 시스템 아키텍처 (`sa/`)

서비스 책임·통신·시퀀스·데이터 소유권·복구·인증을 연결한다. [아키텍처](../design/architecture.md)와 [결제·메시지](../design/payment-and-messaging.md)를 따른다.

<a id="infrastructure"></a>

## 인프라 (`infrastructure/`)

프로세스·네트워크·진입점·DB·Kafka·관측·운영 알림 경로와 포트·볼륨·비밀값·기동 조건을 표시한다. [실행·관측](../design/operations.md)을 따른다.

각 산출물은 편집 원본과 SVG·PDF·HTML 등 열람용 출력을 함께 두고 관련 설계·결정·구현 기록을 연결한다. 목표 구성과 실제 실행·배포 상태, 논리 모델과 물리 모델을 구분한다.
