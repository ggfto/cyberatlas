import { MriPageHeader } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'
import { InfraMap } from './components/InfraMap.jsx'

// F0: esqueleto. O mapa ainda nao tem fonte de dados: um no so, para provar a cadeia
// Quinoa -> Vite -> React Flow + ui-kit dentro do hub. A topologia real chega na F1.
const PLACEHOLDER = {
  nodes: [{ id: 'atlas', position: { x: 0, y: 0 }, data: { label: 'Cyber Atlas: aguardando fontes' } }],
  edges: [],
}

export default function App() {
  return (
    <div className="flex h-full flex-col gap-4 p-4">
      <MriPageHeader title="Cyber Atlas" icon={Network} />
      <InfraMap nodes={PLACEHOLDER.nodes} edges={PLACEHOLDER.edges} />
    </div>
  )
}
