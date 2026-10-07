"""ktlint on the lines this working tree changes (and on new .kt files whole): existing files are not reformatted
as a whole (owner's rule), so only what a change touches has to pass. Run from anywhere in the repository; needs
ktlint.jar at its root. @Composable functions named in UpperCamelCase (standard:function-naming) are accepted."""
import subprocess,re,sys,os
os.chdir(subprocess.run(['git','rev-parse','--show-toplevel'],capture_output=True,text=True,check=True).stdout.strip())
files=[l[3:] for l in subprocess.run(['git','status','--porcelain'],capture_output=True,text=True).stdout.splitlines() if l[3:].endswith('.kt')]
added={}
for f in files:
    st=subprocess.run(['git','ls-files','--error-unmatch',f],capture_output=True).returncode
    if st!=0:
        added[f]=None; continue
    d=subprocess.run(['git','diff','-U0','HEAD','--',f],capture_output=True,text=True).stdout
    s=set()
    for m in re.finditer(r'^@@ -\S+ \+(\d+)(?:,(\d+))? @@',d,re.M):
        a=int(m.group(1)); n=int(m.group(2) or 1)
        s.update(range(a,a+n))
    added[f]=s
out=subprocess.run(['java','-jar','ktlint.jar','--relative']+files,capture_output=True,text=True).stdout
for line in out.splitlines():
    m=re.match(r'(.+?):(\d+):(\d+): (.*)',line)
    if not m: continue
    f,ln=m.group(1),int(m.group(2))
    s=added.get(f,set())
    if s is None or ln in s: print(line)
