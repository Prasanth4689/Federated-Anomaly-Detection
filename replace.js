const fs = require('fs');
const files = ['frontend/src/App.tsx', 'frontend/src/hooks/useWebSocket.ts', 'frontend/src/components/topology/NodeDetailPanel.tsx'];

for (const file of files) {
    let content = fs.readFileSync(file, 'utf8');
    
    // add variable
    if (!content.includes('VITE_API_URL')) {
        const importMatch = content.match(/import.*?;\n/g);
        const lastImport = importMatch[importMatch.length - 1];
        content = content.replace(lastImport, lastImport + "\nconst API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';\n");
    }

    content = content.replace(/'http:\/\/localhost:8080(.*?)'/g, "\\");
    content = content.replace(/http:\/\/localhost:8080(.*?)/g, "\\");
    
    fs.writeFileSync(file, content);
}
