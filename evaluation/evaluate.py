"""BIT-16 reproducible evaluation runner.
Run the application first, then execute: python evaluation/evaluate.py
The script uses the server's authoritative triage endpoint and reports accuracy/F1.
"""
import csv, json, urllib.request, urllib.error
from collections import defaultdict

BASE='http://localhost:8080/api/triage/analyze'
rows=list(csv.DictReader(open('backend/src/main/resources/evaluation/ground_truth.csv',encoding='utf-8')))
exp=[]; pred=[]
for r in rows:
    req=urllib.request.Request(BASE,data=json.dumps({'text':r['text'],'ward':'Ward A / Zone 1'}).encode(),headers={'Content-Type':'application/json'},method='POST')
    try:
        with urllib.request.urlopen(req,timeout=30) as x: body=json.load(x)
        p=body['data']['suggestedCategory']
    except Exception as e:
        print('ERROR',r['id'],e); raise SystemExit(2)
    exp.append(r['expected_category']); pred.append(p)
labels=sorted(set(exp))
accuracy=sum(a==b for a,b in zip(exp,pred))/len(exp)
f1=[]
for label in labels:
    tp=sum(a==label and b==label for a,b in zip(exp,pred)); fp=sum(a!=label and b==label for a,b in zip(exp,pred)); fn=sum(a==label and b!=label for a,b in zip(exp,pred))
    precision=tp/(tp+fp) if tp+fp else 0; recall=tp/(tp+fn) if tp+fn else 0
    if precision+recall: f1.append(2*precision*recall/(precision+recall))
print(json.dumps({'samples':len(rows),'accuracy':round(accuracy,4),'macro_f1':round(sum(f1)/len(f1),4),'labels':labels},indent=2))
