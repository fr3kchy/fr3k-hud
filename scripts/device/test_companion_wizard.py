import os,subprocess,xml.etree.ElementTree as E,re,time
from pathlib import Path
out=Path(os.environ.get('FR3K_EVIDENCE_DIR','evidence/android-pass-2026-09-09-usb'))
out.mkdir(parents=True,exist_ok=True)
def adb(*args): return subprocess.run(['adb',*args],check=True,stdout=subprocess.PIPE,text=True).stdout
def snap(name):
 adb('shell','uiautomator','dump','/sdcard/fr3k-companion.xml')
 adb('pull','/sdcard/fr3k-companion.xml',str(out/(name+'.xml')))
 return list(E.parse(out/(name+'.xml')).iter('node'))
def point(n):
 p=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
 return ((p[0]+p[2])//2,(p[1]+p[3])//2) if len(p)==4 and p[2]>p[0] and p[3]>p[1] else None
def click(nodes,text):
 for n in nodes:
  if n.get('text')==text and (p:=point(n)):
   adb('shell','input','tap',str(p[0]),str(p[1]));return True
 return False
def next_step(nodes,i):
 for _ in range(5):
  if click(nodes,'Next'): return snap(f'companion-wizard-step-{i}')
  adb('shell','input','swipe','550','1850','550','950','300')
  nodes=snap('companion-wizard-scroll')
 raise AssertionError('Next not reachable')
adb('shell','am','force-stop','com.fr3k.blackwave.debug')
adb('shell','am','start','-W','-n','com.fr3k.blackwave.debug/com.fr3k.blackwave.MainActivity')
n=snap('companion-cold-start')
assert click(n,'SET UP BLACKWAVE')
n=snap('companion-wizard-profile-start')
for i in range(2,6):
 n=next_step(n,i)
 texts=[x.get('text','') for x in n]
 assert any(f'· {i}/5' in t for t in texts), (i,texts)
 if i==3:
  field=next(x for x in n if x.get('class')=='android.widget.EditText')
  x,y=point(field);adb('shell','input','tap',str(x),str(y));adb('shell','input','text','invalid-json');
  if 'mInputShown=true' in adb('shell','dumpsys','input_method'): adb('shell','input','keyevent','4')
  n=snap('companion-wizard-invalid-input')
  inspected=False
  for _ in range(4):
   if click(n,'Inspect pairing code'): inspected=True;break
   adb('shell','input','swipe','550','1850','550','1200','300');n=snap('companion-wizard-input-scroll')
  assert inspected, 'Inspect button not reachable'
  n=snap('companion-wizard-invalid-result')
  texts=[x.get('text','') for x in n]
  assert any('cannot be converted' in t or 'Invalid pairing' in t or 'must begin' in t for t in texts),texts
 if i==4:
  assert click(n,'Test connection and fetch fleet')
  n=snap('companion-wizard-unpaired-probe')
  assert any('No saved gateway pairing' in x.get('text','') for x in n)
 if i==5:
  assert any('Pairing incomplete' in x.get('text','') for x in n)
  assert any(x.get('text')=='Open FR3K HUD setup' and x.get('enabled')=='true' for x in n)
print('PASS: five setup steps, invalid QR error, unpaired probe, warning summary, installed HUD launch action available. No pairing changed.')
