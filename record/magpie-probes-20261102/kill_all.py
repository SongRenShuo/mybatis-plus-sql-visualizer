import subprocess
out = subprocess.check_output(["powershell","-Command","Get-CimInstance Win32_Process -Filter \"name='java.exe' or name='fsnotifier.exe'\" | Where-Object { $_.CommandLine -match 'MPSV-MagpieGems' } | ForEach-Object { $_.ProcessId }"]).decode()
for pid in [p.strip() for p in out.splitlines() if p.strip().isdigit()]:
    r = subprocess.run(["taskkill","/F","/PID",pid],capture_output=True,text=True)
    print(pid, r.returncode)
print("DONE")
