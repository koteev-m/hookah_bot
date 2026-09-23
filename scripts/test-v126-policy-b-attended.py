#!/usr/bin/env python3
"""Real owned PTY/process groups for the exact attended bounded-command helper.

Only local Python fixture children run. No SSH, providers, application or daemon.
The same cases run on the isolated Linux validation runtime; macOS is not Linux proof.
"""
import errno
import json
import os
from pathlib import Path
import pty
import re
import select
import signal
import subprocess
import sys
import tempfile
import time
import unittest

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parent
SOURCE=ROOT/'v126-cutover.sh'

LEAF=r'''import json,os,sys,time
from pathlib import Path
mode,ready=sys.argv[1:]
fd=os.open('/dev/tty',os.O_RDWR)
record=dict(pid=os.getpid(),group=os.getpgrp(),foreground=os.tcgetpgrp(fd))
Path(ready).write_text(json.dumps(record))
os.write(fd,b'SYNTHETIC_TTY_READY\n')
if mode=='success':
 value=os.read(fd,64)
 if value!=b'YES\n':raise SystemExit(89)
else:time.sleep(3)
os.close(fd)
print('SYNTHETIC_TTY_DONE')
'''
DRIVER=r'''import json,os,subprocess,sys
from pathlib import Path
helper,leaf,mode,root=sys.argv[1:]
root=Path(root)
before=os.tcgetpgrp(0)
child=subprocess.Popen([sys.executable,helper,('2' if mode=='success' else '.5' if mode=='timeout' else '2'),'--attended',sys.executable,leaf,mode,str(root/'ready.json')])
(root/'helper.pid').write_text(str(child.pid))
result=child.wait(timeout=5)
after=os.tcgetpgrp(0)
(root/'result.json').write_text(json.dumps(dict(status=result,before=before,after=after,owner=os.getpgrp())))
'''


class Attended(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='policy-b-owned-pty-')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve()
        source=SOURCE.read_text()
        marker="cutover_bounded_command() {\n  python3 -c '\n"
        self.assertEqual(source.count(marker),1)
        program=source.split(marker,1)[1].split("\n' \"$@\"\n}",1)[0]
        compile(program,'<actual-attended-helper>','exec')
        self.helper=self.root/'actual-helper.py';self.helper.write_text(program)
        self.leaf=self.root/'leaf.py';self.leaf.write_text(LEAF)
        self.driver=self.root/'driver.py';self.driver.write_text(DRIVER)
        self.env=dict(PATH=os.path.dirname(sys.executable)+':/usr/bin:/bin',HOME=str(self.root),LC_ALL='C',PYTHONDONTWRITEBYTECODE='1')

    def run_owned_pty(self,mode):
        pid,fd=pty.fork()
        if pid==0:
            os.execve(sys.executable,[sys.executable,str(self.driver),str(self.helper),str(self.leaf),mode,str(self.root)],self.env)
        transcript=bytearray();sent=False;cancelled=False;finished=False;deadline=time.monotonic()+7
        try:
            while time.monotonic()<deadline:
                observed,wait_status=os.waitpid(pid,os.WNOHANG)
                if observed:
                    finished=True
                    self.assertEqual(os.waitstatus_to_exitcode(wait_status),0,transcript.decode(errors='replace'))
                    break
                ready,_,_=select.select([fd],[],[],.05)
                if ready:
                    try:payload=os.read(fd,65536)
                    except OSError as error:
                        if error.errno!=errno.EIO:raise
                        payload=b''
                    transcript.extend(payload)
                ready_path=self.root/'ready.json'
                if ready_path.exists():
                    if mode=='success' and not sent:
                        os.write(fd,b'YES\n');sent=True
                    elif mode=='cancel' and not cancelled:
                        helper=int((self.root/'helper.pid').read_text())
                        # The owned driver has not reaped this direct child yet.
                        os.kill(helper,signal.SIGTERM);cancelled=True
            self.assertTrue(finished,'owned PTY exceeded finite fixture bound')
        finally:
            os.close(fd)
            if not finished:
                os.kill(pid,signal.SIGKILL)
                os.waitpid(pid,0)
        result=json.loads((self.root/'result.json').read_text())
        leaf=json.loads((self.root/'ready.json').read_text())
        self.assertEqual(leaf['foreground'],leaf['group'])
        self.assertNotEqual(leaf['group'],result['owner'])
        self.assertEqual(result['before'],result['owner'])
        self.assertEqual(result['after'],result['before'],'foreground console not restored')
        return result,bytes(transcript)

    def test_real_attended_child_owns_console_and_parent_foreground_is_restored(self):
        result,output=self.run_owned_pty('success')
        self.assertEqual(result['status'],0)
        self.assertIn(b'SYNTHETIC_TTY_READY',output)

    def test_real_attended_timeout_is_unknown_and_restores_foreground(self):
        result,output=self.run_owned_pty('timeout')
        self.assertEqual(result['status'],124)
        self.assertIn(b'IO_OUTCOME=UNKNOWN',output)
        self.assertNotIn(b'SYNTHETIC_TTY_DONE',output)

    def test_real_attended_cancel_kills_owned_child_and_restores_foreground(self):
        result,output=self.run_owned_pty('cancel')
        self.assertEqual(result['status'],128+signal.SIGTERM)
        self.assertIn(b'IO_OUTCOME=UNKNOWN',output)
        self.assertNotIn(b'SYNTHETIC_TTY_DONE',output)

    def test_missing_controlling_terminal_refuses_without_launching_attended_leaf(self):
        ready=self.root/'ready.json'
        completed=subprocess.run([sys.executable,str(self.helper),'1','--attended',sys.executable,str(self.leaf),'success',str(ready)],
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True,env=self.env,timeout=3)
        self.assertEqual(completed.returncode,125,completed.stderr)
        self.assertFalse(ready.exists())
        self.assertIn(b'consumer_unavailable',completed.stderr)


if __name__=='__main__':unittest.main(verbosity=2)
