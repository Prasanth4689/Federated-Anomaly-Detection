import os, re
files = ['frontend/src/App.tsx', 'frontend/src/hooks/useWebSocket.ts', 'frontend/src/components/topology/NodeDetailPanel.tsx']

for file in files:
    with open(file, 'r', encoding='utf-8') as f:
        content = f.read()

    # Add API_URL if not exists
    if 'VITE_API_URL' not in content:
        content = content.replace(
            "import {", 
            "const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';\nimport {", 
            1
        )
    
    # Replace 'http://localhost:8080...' with ${API_URL}...
    content = re.sub(r"'http://localhost:8080(.*?)'", r"${API_URL}\1", content)
    
    # Replace http://localhost:8080... with ${API_URL}...
    content = re.sub(r"http://localhost:8080(.*?)", r"${API_URL}\1", content)

    with open(file, 'w', encoding='utf-8') as f:
        f.write(content)
