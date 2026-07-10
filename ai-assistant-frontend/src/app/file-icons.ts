export interface FileIconInfo {
  label: string;
  color: string;
  kind: 'folder' | 'folder-open' | 'file' | 'json' | 'js' | 'ts' | 'java' | 'html' | 'css' | 'md' | 'xml' | 'py' | 'yml' | 'img' | 'lock' | 'env' | 'git';
}

export function getFileIcon(name: string, type: 'file' | 'folder', expanded = false): FileIconInfo {
  if (type === 'folder') {
    return {
      label: expanded ? '📂' : '📁',
      color: '#dcb67a',
      kind: expanded ? 'folder-open' : 'folder'
    };
  }

  const lower = name.toLowerCase();
  const ext = lower.includes('.') ? lower.split('.').pop() || '' : '';

  if (lower === 'package.json' || lower === 'package-lock.json' || ext === 'json') {
    return { label: '{}', color: '#cbcb41', kind: 'json' };
  }
  if (lower === '.gitignore' || lower === '.gitattributes') {
    return { label: '⎇', color: '#f05033', kind: 'git' };
  }
  if (lower.startsWith('.env')) {
    return { label: '⚙', color: '#89d185', kind: 'env' };
  }
  if (ext === 'ts' || ext === 'tsx') {
    return { label: 'TS', color: '#3178c6', kind: 'ts' };
  }
  if (ext === 'js' || ext === 'jsx' || ext === 'mjs' || ext === 'cjs') {
    return { label: 'JS', color: '#f0db4f', kind: 'js' };
  }
  if (ext === 'java') {
    return { label: 'J', color: '#e76f00', kind: 'java' };
  }
  if (ext === 'html' || ext === 'htm') {
    return { label: '<>', color: '#e44d26', kind: 'html' };
  }
  if (ext === 'css' || ext === 'scss' || ext === 'sass' || ext === 'less') {
    return { label: '#', color: '#264de4', kind: 'css' };
  }
  if (ext === 'md' || ext === 'markdown') {
    return { label: 'M↓', color: '#519aba', kind: 'md' };
  }
  if (ext === 'xml' || ext === 'svg') {
    return { label: '<>', color: '#8bc34a', kind: 'xml' };
  }
  if (ext === 'py') {
    return { label: 'Py', color: '#3572a5', kind: 'py' };
  }
  if (ext === 'yml' || ext === 'yaml') {
    return { label: 'Y', color: '#cb171e', kind: 'yml' };
  }
  if (ext === 'png' || ext === 'jpg' || ext === 'jpeg' || ext === 'gif' || ext === 'webp' || ext === 'ico') {
    return { label: '🖼', color: '#a074c4', kind: 'img' };
  }
  if (ext === 'lock') {
    return { label: '🔒', color: '#a6adbb', kind: 'lock' };
  }
  if (ext === 'properties' || ext === 'ini' || ext === 'conf') {
    return { label: '⚙', color: '#89d185', kind: 'env' };
  }

  return { label: '🗎', color: '#c5c5c5', kind: 'file' };
}

export function languageFromPath(path: string): string {
  const name = path.toLowerCase();
  const ext = name.includes('.') ? name.split('.').pop() || '' : '';

  switch (ext) {
    case 'ts':
    case 'tsx':
      return 'typescript';
    case 'js':
    case 'jsx':
    case 'mjs':
    case 'cjs':
      return 'javascript';
    case 'java':
      return 'java';
    case 'json':
      return 'json';
    case 'html':
    case 'htm':
      return 'html';
    case 'css':
      return 'css';
    case 'scss':
      return 'scss';
    case 'md':
    case 'markdown':
      return 'markdown';
    case 'xml':
    case 'svg':
      return 'xml';
    case 'py':
      return 'python';
    case 'yml':
    case 'yaml':
      return 'yaml';
    case 'sql':
      return 'sql';
    case 'sh':
    case 'bash':
      return 'shell';
    case 'properties':
      return 'ini';
    default:
      return 'plaintext';
  }
}
