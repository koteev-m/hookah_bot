// Real, deterministic classic-xref PDFs; every page has a different painted rectangle.
export function infoMediaPdf(pageCount = 2, activeContent = false, transferFilter = false) {
  const objects = [
    `<< /Type /Catalog /Pages 2 0 R${activeContent ? ' /OpenAction << /S /JavaScript /JS (app.alert("unsafe")) >>' : ''} >>`,
    `<< /Type /Pages /Kids [${Array.from({ length: pageCount }, (_, i) => `${3 + i * 2} 0 R`).join(' ')}] /Count ${pageCount} >>`
  ]
  for (let index = 0; index < pageCount; index++) {
    const content = `${transferFilter ? '/GS1 gs\n' : ''}${index % 2 ? '0.1 0.2 0.8' : '0.8 0.2 0.1'} rg 20 20 260 360 re f\n1 1 1 rg BT /F1 18 Tf 40 200 Td (Page ${index + 1}) Tj ET\n`
    const graphics = transferFilter ? '/ExtGState << /GS1 << /TR << /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >> >> >>' : ''
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 ${3 + pageCount * 2} 0 R >> ${graphics} >> /Contents ${4 + index * 2} 0 R >>`)
    objects.push(`<< /Length ${Buffer.byteLength(content)} >>\nstream\n${content}endstream`)
  }
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
  let pdf = '%PDF-1.4\n'
  const offsets = [0]
  objects.forEach((object, index) => {
    offsets.push(Buffer.byteLength(pdf))
    pdf += `${index + 1} 0 obj\n${object}\nendobj\n`
  })
  const xref = Buffer.byteLength(pdf)
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  pdf += offsets.slice(1).map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(pdf)
}
