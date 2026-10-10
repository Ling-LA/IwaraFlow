"""Record the actual Android 16 display without competing for its MediaCodec."""
from pathlib import Path
import subprocess
import re

out = Path('promo-recordings').resolve()
export = '/sdcard/Download/IwaraFlow-Android16-promo'

def adb(*args):
    result = subprocess.run(['adb', *args], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    print(result.stdout, flush=True)
    if 'KO:' in result.stdout:
        raise RuntimeError(result.stdout)

command = ['adb','shell','am','instrument','-w','-r','-e','class',
           'com.ling.iwaraflow.PromoRecordingTest','com.ling.iwaraflow.test/androidx.test.runner.AndroidJUnitRunner']
process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
active = None
try:
    with (out/'instrumentation.txt').open('w') as log:
        for line in process.stdout:
            print(line, end='', flush=True); log.write(line); log.flush()
            start = re.search(r'promo_start=([a-z0-9-]+),(\d+)', line)
            stop = re.search(r'promo_stop=([a-z0-9-]+)', line)
            if start:
                active = start[1]
                adb('emu','screenrecord','start','--time-limit','120',str(out/(active+'.webm')))
                adb('shell','touch',f'{export}/{active}.ready')
            elif stop:
                adb('emu','screenrecord','stop')
                assert (out/(active+'.webm')).stat().st_size > 10000
                adb('shell','touch',f'{export}/{active}.done')
                active = None
    if process.wait(): raise RuntimeError('Instrumentation command failed')
finally:
    if active:
        subprocess.run(['adb','emu','screenrecord','stop'])
