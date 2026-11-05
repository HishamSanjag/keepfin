export function Skeleton({rows = 6}: {rows?: number}) {
  return (
    <div className="lib-skeleton" aria-busy="true" aria-label="Loading your library">
      {Array.from({length: rows}, (_, index) => (
        <div key={index} className="sk-row">
          <div className="sk sk-chip" style={{width: 44, height: 44}} />
          <div className="sk sk-line" style={{width: `${60 - (index % 4) * 8}%`}} />
          <div className="sk sk-line" style={{width: '10%', marginLeft: 'auto'}} />
        </div>
      ))}
    </div>
  )
}
