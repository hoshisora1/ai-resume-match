interface PlaceholderPageProps {
  title: string
}

export function PlaceholderPage({ title }: PlaceholderPageProps) {
  return (
    <section aria-labelledby="page-title" className="placeholder-page">
      <h1 id="page-title">{title}</h1>
    </section>
  )
}
