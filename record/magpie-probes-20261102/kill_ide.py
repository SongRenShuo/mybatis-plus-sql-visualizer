import subprocess,time
for pid in (70284, 74916, 5436):
    r = subprocess.run(["taskkill","/F","/PID",str(pid)],capture_output=True,text=True)
    print(pid, r.returncode, (r.stdout or r.stderr).strip()[:80])
time.sleep(2)
print("DONE")
