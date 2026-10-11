"""Read-only exported XLSX validation and layout approximation, not native Excel QA."""
from pathlib import Path
import json, zipfile, xml.etree.ElementTree as ET, re, hashlib
from PIL import Image, ImageDraw, ImageFont
base=Path('outputs/01a124de-b755-76c1-a212-a325ffee9c34/submission')
e=json.loads(Path('docs/measurements/integrated/evidence.json').read_text(encoding='utf-8'))
ns={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
q=lambda s:'{'+ns['m']+'}'+s
z=zipfile.ZipFile(base/'submission-evidence.xlsx')
strings=[]
if 'xl/sharedStrings.xml' in z.namelist():strings=[''.join(t.text or '' for t in s.iter(q('t'))) for s in ET.fromstring(z.read('xl/sharedStrings.xml'))]
styles=ET.fromstring(z.read('xl/styles.xml'));fonts=styles.find('m:fonts',ns);fills=styles.find('m:fills',ns);formats=styles.find('m:cellXfs',ns)
num_formats=styles.find('m:numFmts',ns)
custom={int(n.attrib['numFmtId']):n.attrib['formatCode'] for n in ([] if num_formats is None else num_formats)}
names=[s.attrib['name'] for s in ET.fromstring(z.read('xl/workbook.xml')).find('m:sheets',ns)]
def val(c):
    if c is None:return ''
    v=c.find('m:v',ns);typ=c.attrib.get('t')
    if typ=='inlineStr':return ''.join(t.text or '' for t in c.iter(q('t')))
    if v is None:return ''
    if typ=='s':return strings[int(v.text)]
    if typ in ('str','e','d'):return v.text or ''
    return float(v.text) if v.text else ''
def idx(s):
    n=0
    for c in s:n=n*26+ord(c)-64
    return n
def color(n,default):return '#'+n.attrib['rgb'][-6:] if n is not None and 'rgb' in n.attrib else default
errors=[];clipped=[];sheet_cells={};previews=[]
for i,name in enumerate(names,1):
    root=ET.fromstring(z.read(f'xl/worksheets/sheet{i}.xml'));cells={c.attrib['r']:c for c in root.iter(q('c'))};sheet_cells[name]=cells
    errors.extend(f'{name}!{k}' for k,c in cells.items() if c.attrib.get('t')=='e')
    ranges={'Summary':[('A','F',2,28)],'Candidates':[('A','F',2,14)],'Cohorts':[('B','K',6,14)],'Attempts':[('C','O',5,14)],'Runs':[('C','I',6,14)]}[name]
    if name=='Candidates':
        rr=6+sum(len(c['timeoutCandidates']) for c in e['cohorts'])+4
        ranges.extend([('A','H',rr,rr+8),('A','G',rr+11,rr+16)])
    for part,(l,r,top,bottom) in enumerate(ranges,1):
        lo,hi=idx(l),idx(r);width={c:70 for c in range(lo,hi+1)}
        column_defs=root.find('m:cols',ns)
        for col in ([] if column_defs is None else column_defs):
            for c in range(max(lo,int(col.attrib['min'])),min(hi,int(col.attrib['max']))+1):width[c]=round(float(col.attrib.get('width',9))*7+5)
        height={r:33 for r in range(top,bottom+1)}
        for row in root.find('m:sheetData',ns):
            if int(row.attrib['r']) in height:height[int(row.attrib['r'])]=round(float(row.attrib.get('ht',25))*4/3)
        xs={lo:10};ys={top:35}
        for c in width:xs[c+1]=xs[c]+width[c]
        for r in height:ys[r+1]=ys[r]+height[r]
        im=Image.new('RGB',(xs[hi+1]+10,ys[bottom+1]+10),'white');draw=ImageDraw.Draw(im)
        draw.text((10,7),f'{name} - XML layout preview (not native Excel)',font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',13),fill='#526579')
        for addr,cell in cells.items():
            c=idx(re.sub('[0-9]','',addr));r=int(re.sub('[A-Z]','',addr))
            if c not in width or r not in height:continue
            st=formats[int(cell.attrib.get('s',0))];ft=fonts[int(st.attrib.get('fontId',0))];fill=fills[int(st.attrib.get('fillId',0))]
            bg=color(fill.find('m:patternFill/m:fgColor',ns),'#FFFFFF');fg=color(ft.find('m:color',ns),'#243B53')
            v=val(cell);txt=str(v);fmt=custom.get(int(st.attrib.get('numFmtId',0)),'')
            if isinstance(v,float):txt=f'{v*100:.1f}%' if '%' in fmt else f'{v:,.3f}' if '.000' in fmt else str(int(v)) if v.is_integer() else str(v)
            size=ft.find('m:sz',ns);font=ImageFont.truetype('C:/Windows/Fonts/malgun.ttf',round(float(size.attrib.get('val',10))*4/3) if size is not None else 13)
            x,y,cw,ch=xs[c],ys[r],width[c],height[r];draw.rectangle((x,y,x+cw,y+ch),fill=bg)
            # Text can overflow empty adjacent cells, but not populated cells.
            available=cw-10
            for nextcol in range(c+1,hi+1):
                nextaddr='';n=nextcol
                while n:nextaddr=chr(65+(n-1)%26)+nextaddr;n=(n-1)//26
                if val(cells.get(f'{nextaddr}{r}'))!='':break
                available+=width[nextcol]
            if draw.textlength(txt,font=font)>available:clipped.append(f'{name}!{addr}')
            draw.text((x+5,y+max(2,(ch-font.size)/2-2)),txt,font=font,fill=fg)
        target=base/f'{name}-{part}-fallback.png';im.save(target);previews.append(target.name)
assert not errors,errors
a=sheet_cells['Attempts'];assert len([k for k in a if re.fullmatch(r'A\d+',k) and int(k[1:])>=6])==65
for row,original in enumerate(e['attempts'],6):
    assert val(a[f'A{row}'])==original['runId']
    assert val(a[f'D{row}'])==original['endpoint']
    assert val(a[f'E{row}'])==original['outcome']
    assert val(a[f'G{row}'])==original['elapsedMs']
    assert val(a[f'F{row}'])==(original['httpCode'] if original['httpCode'] is not None else '')
    assert val(a[f'P{row}'])=='UTC '+original['startedAt'] and val(a[f'Q{row}'])=='UTC '+original['endedAt']
for source in e['sources']:assert hashlib.sha256((Path('docs/measurements/raw')/source['file']).read_bytes()).hexdigest()==source['sha256']
result={'rawRows':65,'uniqueRuns':39,'formulaErrors':errors,'clippedPreviewCells':clipped,'previews':previews,'visual':'XML_APPROXIMATION_ONLY','nativeExcel':'NOT_TESTED','sourceHashes':'PASS','exactExportedValues':'PASS'}
(base/'xml-validation.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))
