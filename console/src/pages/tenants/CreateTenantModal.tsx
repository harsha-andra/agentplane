import { useEffect, useState } from 'react'
import { useCreateTenantMutation } from '../../api/tenants'
import { Modal } from '../../components/Modal'
import type { CreateTenantInput } from '../../types/api'

export interface CreateTenantModalProps {
  onClose: () => void
}

function slugify(s: string): string {
  return s
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

type FormState = CreateTenantInput

function validate(values: FormState): Partial<Record<keyof FormState, string>> {
  const errors: Partial<Record<keyof FormState, string>> = {}
  if (!values.name.trim()) errors.name = 'Name is required'
  if (!values.slug.trim()) errors.slug = 'Slug is required'
  else if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(values.slug)) errors.slug = 'Lowercase letters, numbers, hyphens only'
  if (!values.namespace.trim()) errors.namespace = 'Namespace is required'
  if (!values.quotaCpu.trim()) errors.quotaCpu = 'CPU quota is required'
  if (!values.quotaMemory.trim()) errors.quotaMemory = 'Memory quota is required'
  if (!Number.isFinite(values.maxConcurrentRuns) || values.maxConcurrentRuns < 1) {
    errors.maxConcurrentRuns = 'Must be at least 1'
  }
  return errors
}

export function CreateTenantModal({ onClose }: CreateTenantModalProps) {
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [slugTouched, setSlugTouched] = useState(false)
  const [namespace, setNamespace] = useState('')
  const [namespaceTouched, setNamespaceTouched] = useState(false)
  const [quotaCpu, setQuotaCpu] = useState('16')
  const [quotaMemory, setQuotaMemory] = useState('64Gi')
  const [maxConcurrentRuns, setMaxConcurrentRuns] = useState(10)
  const [submitted, setSubmitted] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const mutation = useCreateTenantMutation()

  const derivedSlug = slugify(name)
  useEffect(() => {
    if (!slugTouched) setSlug(derivedSlug)
  }, [derivedSlug, slugTouched])
  useEffect(() => {
    if (!namespaceTouched) setNamespace(derivedSlug ? `agentplane-${derivedSlug}` : '')
  }, [derivedSlug, namespaceTouched])

  const values: FormState = { name, slug, namespace, quotaCpu, quotaMemory, maxConcurrentRuns }
  const errors = validate(values)
  const hasErrors = Object.keys(errors).length > 0

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    setSubmitError(null)
    if (hasErrors) return
    mutation.mutate(values, {
      onSuccess: () => onClose(),
      onError: (err) => setSubmitError(err instanceof Error ? err.message : 'Failed to create tenant'),
    })
  }

  return (
    <Modal
      title="New tenant"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn btn-sm" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="create-tenant-form" className="btn btn-sm btn-primary" disabled={mutation.isPending}>
            {mutation.isPending ? 'Creating…' : 'Create tenant'}
          </button>
        </>
      }
    >
      <form id="create-tenant-form" onSubmit={onSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }} noValidate>
        <div className="field">
          <label className="field-label" htmlFor="tenant-name">
            Name
          </label>
          <input id="tenant-name" className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Acme Robotics" />
          {submitted && errors.name && <span className="field-error">{errors.name}</span>}
        </div>

        <div className="field">
          <label className="field-label" htmlFor="tenant-slug">
            Slug
          </label>
          <input
            id="tenant-slug"
            className="input mono"
            value={slug}
            onChange={(e) => {
              setSlugTouched(true)
              setSlug(e.target.value)
            }}
            placeholder="acme-robotics"
          />
          {submitted && errors.slug ? <span className="field-error">{errors.slug}</span> : <span className="field-hint">Used to derive the tenant id.</span>}
        </div>

        <div className="field">
          <label className="field-label" htmlFor="tenant-namespace">
            Kubernetes namespace
          </label>
          <input
            id="tenant-namespace"
            className="input mono"
            value={namespace}
            onChange={(e) => {
              setNamespaceTouched(true)
              setNamespace(e.target.value)
            }}
            placeholder="agentplane-acme-robotics"
          />
          {submitted && errors.namespace && <span className="field-error">{errors.namespace}</span>}
        </div>

        <div style={{ display: 'flex', gap: 12 }}>
          <div className="field" style={{ flex: 1 }}>
            <label className="field-label" htmlFor="tenant-cpu">
              CPU quota
            </label>
            <input id="tenant-cpu" className="input mono" value={quotaCpu} onChange={(e) => setQuotaCpu(e.target.value)} placeholder="32" />
            {submitted && errors.quotaCpu && <span className="field-error">{errors.quotaCpu}</span>}
          </div>
          <div className="field" style={{ flex: 1 }}>
            <label className="field-label" htmlFor="tenant-memory">
              Memory quota
            </label>
            <input id="tenant-memory" className="input mono" value={quotaMemory} onChange={(e) => setQuotaMemory(e.target.value)} placeholder="128Gi" />
            {submitted && errors.quotaMemory && <span className="field-error">{errors.quotaMemory}</span>}
          </div>
        </div>

        <div className="field">
          <label className="field-label" htmlFor="tenant-max-runs">
            Max concurrent runs
          </label>
          <input
            id="tenant-max-runs"
            type="number"
            min={1}
            className="input mono"
            value={maxConcurrentRuns}
            onChange={(e) => setMaxConcurrentRuns(Number(e.target.value))}
          />
          {submitted && errors.maxConcurrentRuns && <span className="field-error">{errors.maxConcurrentRuns}</span>}
        </div>

        {submitError && <span className="field-error">{submitError}</span>}
      </form>
    </Modal>
  )
}
