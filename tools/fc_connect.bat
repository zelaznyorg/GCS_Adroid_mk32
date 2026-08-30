@echo off
REM DRON 15 - polaczenie z FC po COM. Uruchamiac dwuklikiem albo z konsoli.
REM Opcjonalny argument: numer portu, np.  fc_connect.bat COM7
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo === DRON 15 : polaczenie z kontrolerem lotu ===
echo.

python --version >nul 2>&1
if errorlevel 1 (
    echo BLAD: brak Pythona w PATH. Zainstaluj Python 3 z python.org
    echo (zaznacz "Add python.exe to PATH" przy instalacji^)
    pause
    exit /b 1
)

python -c "import pymavlink, serial" >nul 2>&1
if errorlevel 1 (
    echo Instaluje pymavlink + pyserial...
    python -m pip install --quiet --upgrade pymavlink pyserial
    if errorlevel 1 (
        echo BLAD instalacji pakietow.
        pause
        exit /b 1
    )
)

echo UWAGA: zamknij Mission Planner / QGroundControl - trzymaja port COM.
echo.
python "%~dp0fc_connect.py" %1 %2
echo.
pause
