import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';

class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }
  static getDerivedStateFromError(error) {
    return { error };
  }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: '32px', fontFamily: 'sans-serif', color: '#8c4138', background: '#fbefec', margin: '24px', borderRadius: '8px', border: '1px solid #ecd2cb' }}>
          <h2>Giao diện gặp lỗi khi hiển thị</h2>
          <pre style={{ whiteSpace: 'pre-wrap', fontSize: '13px' }}>{String(this.state.error?.stack || this.state.error)}</pre>
          <button onClick={() => window.location.reload()}>Tải lại trang</button>
        </div>
      );
    }
    return this.props.children;
  }
}

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>
);
