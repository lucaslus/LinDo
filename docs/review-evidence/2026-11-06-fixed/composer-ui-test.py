import subprocess,time,re,json,xml.etree.ElementTree as E
from pathlib import Path
A=[str(Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s','emulator-5560']
O=Path(__file__).resolve().parents[3]/'build/fixed-composer-ui';O.mkdir(parents=True,exist_ok=True)
def cmd(*a):return subprocess.run(A+list(a),capture_output=True,check=True).stdout
def tree():
 cmd('shell','uiautomator','dump','/sdcard/fixed-ui.xml');return E.fromstring(cmd('exec-out','cat','/sdcard/fixed-ui.xml'))
def find(text,root=None):
 for n in (root if root is not None else tree()).iter('node'):
  if n.get('text')==text or n.get('content-desc')==text:return n
 raise AssertionError('Missing '+text)
def wait(text):
 for _ in range(12):
  try:return find(text)
  except AssertionError:time.sleep(.4)
 raise AssertionError('Timeout '+text)
def tap(text):
 n=wait(text);x,y,xx,yy=map(int,re.findall(r'\d+',n.get('bounds')));cmd('shell','input','tap',str((x+xx)//2),str((y+yy)//2))
def start(scene):cmd('shell','am','start','-S','-n','do_.linux.lindo/.MainActivity','--es','lindo.preview',scene)
def shot(name): (O/(name+'.png')).write_bytes(cmd('exec-out','screencap','-p'))
def texts():return ' '.join(n.get('text','') for n in tree().iter('node'))
def enter_body(value):
 fields=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText']
 assert fields
 n=fields[-1];x,y,xx,yy=map(int,re.findall(r'\d+',n.get('bounds')));cmd('shell','input','tap',str((x+xx)//2),str((y+yy)//2));cmd('shell','input','text',value)
results=[]
def case(name,f):
 try:f();results.append({'case':name,'passed':True})
 except Exception as e:shot('failure-'+name);results.append({'case':name,'passed':False,'error':str(e)})
 (O/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
 print(results[-1],flush=True)
def editor():
 start('composer-edit');wait('original markdown from server') if False else wait('编辑内容')
 for _ in range(10):
  if '**original markdown from server**' in texts():break
 enter_body('1234567890');time.sleep(2)
 assert '1234567890' in texts();find('保存修改');shot('edit-keyboard')
 tap('关闭编辑器');wait('保留草稿？');tap('保留草稿并退出');tap('重新打开编辑器')
 for _ in range(10):
  if '1234567890' in texts():break
 assert '1234567890' in texts();shot('edit-restored')
def deletion():
 start('composer-reader');tap('更多楼层操作')
 # The first floor has no delete permission. Dismiss and select the second floor explicitly.
 cmd('shell','input','keyevent','KEYCODE_BACK')
 r=tree();menus=[n for n in r.iter('node') if n.get('content-desc')=='更多楼层操作'];n=menus[-1]
 x,y,xx,yy=map(int,re.findall(r'\d+',n.get('bounds')));cmd('shell','input','tap',str((x+xx)//2),str((y+yy)//2))
 tap('删除评论');wait('删除第 #2 楼？');shot('delete-confirmation');tap('取消')
 assert '删除=0' in texts()
 n=[n for n in tree().iter('node') if n.get('content-desc')=='更多楼层操作'][-1];x,y,xx,yy=map(int,re.findall(r'\d+',n.get('bounds')));cmd('shell','input','tap',str((x+xx)//2),str((y+yy)//2))
 tap('删除评论');tap('确认删除');time.sleep(.5);assert '删除=1' in texts()
def archive():
 start('composer-messages');tap('归档');wait('归档这段会话？');shot('archive-confirmation');tap('取消');assert '归档确认示例' in texts()
 tap('归档');tap('确认归档');time.sleep(.5);assert '归档确认示例' not in texts()
case('editor-recovery-and-keyboard',editor)
case('delete-confirmation',deletion)
case('archive-confirmation',archive)
print('done')
