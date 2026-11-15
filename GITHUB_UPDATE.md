# GitHub 업데이트 순서

이 저장소의 main은 https://github.com/dbghkschl-byte/RestSync 로 연결됩니다.

1. 코드를 수정하고 필요한 빌드·검증을 완료합니다.
2. 팀 서명 키로 새 release APK를 생성합니다.
3. apk/SYNCLAB_RestSync_release.apk를 새 파일로 교체합니다.
4. README의 버전·SHA-256·검증 범위를 실제 결과에 맞게 갱신합니다.
5. 소스·APK·문서를 함께 Commit하고 origin/main으로 Push합니다.
6. GitHub에서 APK 경로와 최종 커밋 전체 해시를 확인하여 제출 페이지와 접수 메일에 반영합니다.

서명 키, 비밀번호, 개인 API 키, local.properties, IDE 캐시는 저장소에 올리지 않습니다. force push는 필요하지 않습니다.
