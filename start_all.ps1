@"
# Start all FedApp-SOC services in separate windows automatically

Write-Host "Starting ML Service (Port 5000)..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd D:\FedApp-SOC\ml-service; py -m uvicorn main:app --reload --port 5000"

Write-Host "Waiting 5 seconds for ML Service to initialize..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

Write-Host "Starting Backend Service (Port 8080)..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd D:\FedApp-SOC\backend; .\mvnw.cmd spring-boot:run"

Write-Host "Waiting 15 seconds for Backend to initialize..." -ForegroundColor Yellow
Start-Sleep -Seconds 15

Write-Host "Starting Frontend Service (Port 5173)..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd D:\FedApp-SOC\frontend; npm run dev"

Write-Host "All services launched! Opening browser..." -ForegroundColor Green
Start-Sleep -Seconds 3
Start-Process "http://localhost:5173"
"@
