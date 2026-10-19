import React from 'react'
import { createRoot } from 'react-dom/client'
// O CSS do kit e importado pelo index.css (camada `kit`); ver a nota la.
import './index.css'
import App from './App.jsx'

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
