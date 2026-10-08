$env:JAVA_HOME = 'C:\Users\HAZEM\.jdks\openjdk-22.0.1'
$env:PATH = 'C:\Users\HAZEM\.jdks\openjdk-22.0.1\bin;' + $env:PATH
Write-Host "Building..."
& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -eq 0) {
    Write-Host "Build SUCCESS. Installing..."
    & 'D:\Android\Sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
    Write-Host "Launching app..."
    & 'D:\Android\Sdk\platform-tools\adb.exe' shell monkey -p com.example.testing -c android.intent.category.LAUNCHER 1
} else {
    Write-Host "Build FAILED with exit code $LASTEXITCODE"
}
