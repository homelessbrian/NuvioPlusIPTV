"""Extract the exact function, retaining the Kotlin implementation rather than transcribing it."""
from pathlib import Path
import argparse,hashlib,json,subprocess

root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--repo',type=Path,required=True)
parser.add_argument('--output',type=Path,default=root/'out')
parser.add_argument('--baseline',default='e4d3d6e801c79835e53d3d967339268029f587b2')
args=parser.parse_args()
repo=args.repo.resolve()
root=args.output.resolve()
root.mkdir(parents=True,exist_ok=True)
relative='app/src/main/java/com/nuvio/tv/livetv/sports/SportsCatalog.kt'
base=args.baseline
before=subprocess.check_output(['git','show',base+':'+relative],cwd=repo).decode('utf-8')
after_bytes=(repo/relative).read_bytes()
after=after_bytes.decode('utf-8').replace('\r\n','\n')

def extract(source):
    start=source.index('    fun hasWord(')
    header_end=source.index('\n',start)
    if '{' not in source[start:header_end]:
        return source[start:header_end].strip()+'\n'
    opened=source.index('{',start)
    depth=1
    cursor=opened+1
    while depth:
        if source[cursor]=='{': depth+=1
        elif source[cursor]=='}': depth-=1
        cursor+=1
    return source[start:cursor].strip()+'\n'

metadata={'baseline_commit':base,'source_path':relative,'helpers':{}}
metadata['candidate_source_file_bytes_sha256']=hashlib.sha256(after_bytes).hexdigest()
for name,source in [('Baseline',before),('Candidate',after)]:
    function=extract(source)
    (root/(name+'.kt')).write_text('package sports.bench\n\nobject '+name+' {\n'+function+'}\n',encoding='utf-8')
    metadata['helpers'][name]={'function_sha256':hashlib.sha256(function.encode()).hexdigest(),'source_sha256':hashlib.sha256(source.encode()).hexdigest(),'function':function}
assert metadata['helpers']['Baseline']['function_sha256']!=metadata['helpers']['Candidate']['function_sha256'], 'Candidate has not changed/frozen yet'
(root/'helper-source-metadata.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
print(json.dumps({name: info['function_sha256'] for name,info in metadata['helpers'].items()}))
