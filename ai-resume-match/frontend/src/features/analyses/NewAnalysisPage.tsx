import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { FileText, FileUp, Send } from 'lucide-react'
import {
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
  type FormEvent,
} from 'react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { useNavigate } from 'react-router'

import { analysisSummaryQueryKey } from '../dashboard/useAnalysisSummaryQuery'
import { createAnalysisSubmission } from '../../shared/api/analyses'
import { ApiError } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import {
  analysisFormSchema,
  hasNonJavaUnicodeWhitespaceCodePoint,
  type AnalysisFormValues,
} from './analysisFormSchema'
import './analysis-form.css'

const FILE_INPUT_ID = 'analysis-resume-file'
const FILE_HELP_ID = 'analysis-resume-help'
const FILE_ERROR_ID = 'analysis-resume-error'
const TITLE_INPUT_ID = 'analysis-job-title'
const TITLE_HELP_ID = 'analysis-job-title-help'
const TITLE_ERROR_ID = 'analysis-job-title-error'
const CONTENT_INPUT_ID = 'analysis-job-content'
const CONTENT_HELP_ID = 'analysis-job-content-help'
const CONTENT_ERROR_ID = 'analysis-job-content-error'

function fieldDescription(helpId: string, errorId: string, hasError: boolean) {
  return hasError ? `${helpId} ${errorId}` : helpId
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) {
    return `${bytes} B`
  }

  const kilobytes = bytes / 1024
  if (kilobytes < 1024) {
    return `${new Intl.NumberFormat('zh-CN', {
      maximumFractionDigits: 1,
    }).format(kilobytes)} KB`
  }

  return `${new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: 1,
  }).format(kilobytes / 1024)} MB`
}

export function NewAnalysisPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const dragDepthRef = useRef(0)
  const submissionInFlightRef = useRef(false)
  const [isDragging, setIsDragging] = useState(false)
  const {
    control,
    formState: { errors },
    getValues,
    handleSubmit,
    register,
    reset,
    resetField,
    setValue,
  } = useForm<AnalysisFormValues>({
    resolver: zodResolver(analysisFormSchema),
    defaultValues: {
      jobTitle: '',
      jobContent: '',
    },
  })
  const selectedFile = useWatch({ control, name: 'file' })
  const jobTitle = useWatch({ control, name: 'jobTitle' }) ?? ''
  const jobTitleRegistration = register('jobTitle')
  const jobContentRegistration = register('jobContent')

  const submissionMutation = useMutation({
    mutationFn: async () => ({
      taskId: (await createAnalysisSubmission(getValues())).taskId,
    }),
    onSuccess: async ({ taskId }) => {
      reset()
      if (fileInputRef.current !== null) {
        fileInputRef.current.value = ''
      }
      dragDepthRef.current = 0
      setIsDragging(false)

      await Promise.all([
        queryClient.invalidateQueries({ queryKey: analysisSummaryQueryKey }),
        queryClient.invalidateQueries({ queryKey: ['analyses'] }),
      ])
      await navigate(`/analyses/${taskId}`, { replace: true })
    },
    onSettled: () => {
      submissionInFlightRef.current = false
    },
  })

  const chooseFile = (file: File | undefined) => {
    if (submissionMutation.isPending) {
      return
    }

    if (file === undefined) {
      resetField('file')
      return
    }

    setValue('file', file, {
      shouldDirty: true,
      shouldTouch: true,
      shouldValidate: true,
    })
  }

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    chooseFile(event.currentTarget.files?.[0])
  }

  const handleDragEnter = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (submissionMutation.isPending) {
      return
    }
    dragDepthRef.current += 1
    setIsDragging(true)
  }

  const handleDragLeave = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (submissionMutation.isPending) {
      return
    }
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
    if (dragDepthRef.current === 0) {
      setIsDragging(false)
    }
  }

  const handleDragOver = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = submissionMutation.isPending
      ? 'none'
      : 'copy'
  }

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (submissionMutation.isPending) {
      return
    }
    dragDepthRef.current = 0
    setIsDragging(false)
    if (fileInputRef.current !== null) {
      fileInputRef.current.value = ''
    }
    chooseFile(event.dataTransfer.files[0])
  }

  const submitForm = () => {
    if (submissionInFlightRef.current) {
      return
    }

    submissionInFlightRef.current = true
    dragDepthRef.current = 0
    setIsDragging(false)
    submissionMutation.reset()
    submissionMutation.mutate()
  }

  const handleFormSubmit = (event: FormEvent<HTMLFormElement>) => {
    void handleSubmit(submitForm)(event)
  }

  const requestId =
    submissionMutation.error instanceof ApiError
      ? submissionMutation.error.requestId
      : undefined
  const confirmationTitle = hasNonJavaUnicodeWhitespaceCodePoint(jobTitle)
    ? jobTitle
    : ''

  return (
    <section
      aria-labelledby="new-analysis-heading"
      className="new-analysis-page"
    >
      <header className="new-analysis-page__header">
        <h1 id="new-analysis-heading">新建分析</h1>
      </header>

      <form
        aria-label="新建分析表单"
        className="analysis-form"
        noValidate
        onSubmit={handleFormSubmit}
      >
        <section
          aria-labelledby="analysis-resume-heading"
          className="analysis-form__section"
        >
          <div className="analysis-form__section-heading">
            <h2 id="analysis-resume-heading">简历文件</h2>
            <span aria-hidden="true" className="analysis-form__step">
              01
            </span>
          </div>

          <div
            aria-disabled={submissionMutation.isPending || undefined}
            aria-labelledby="analysis-resume-heading"
            className="analysis-upload-zone"
            data-dragging={isDragging}
            data-testid="analysis-upload-zone"
            onDragEnter={handleDragEnter}
            onDragLeave={handleDragLeave}
            onDragOver={handleDragOver}
            onDrop={handleDrop}
            role="group"
          >
            <FileUp aria-hidden="true" className="analysis-upload-zone__icon" size={28} />
            <div className="analysis-upload-zone__actions">
              <label
                aria-disabled={submissionMutation.isPending || undefined}
                className="button button--secondary analysis-file-label"
                htmlFor={FILE_INPUT_ID}
              >
                <FileUp aria-hidden="true" size={16} />
                <span>选择简历文件</span>
              </label>
              <span className="analysis-upload-zone__or">或拖放到这里</span>
            </div>
            <Controller
              control={control}
              name="file"
              render={({ field }) => (
                <input
                  accept=".pdf,.docx"
                  aria-describedby={fieldDescription(
                    FILE_HELP_ID,
                    FILE_ERROR_ID,
                    errors.file !== undefined,
                  )}
                  aria-invalid={errors.file ? true : undefined}
                  className="analysis-file-input"
                  disabled={submissionMutation.isPending}
                  id={FILE_INPUT_ID}
                  name={field.name}
                  onBlur={field.onBlur}
                  onChange={handleFileChange}
                  ref={(element) => {
                    field.ref(element)
                    fileInputRef.current = element
                  }}
                  type="file"
                />
              )}
            />
            <p className="analysis-field-help" id={FILE_HELP_ID}>
              支持 PDF、DOCX，文件不超过 5 MB
            </p>
            {errors.file ? (
              <p className="analysis-field-error" id={FILE_ERROR_ID}>
                {errors.file.message}
              </p>
            ) : null}
          </div>

          <div
            aria-label={selectedFile ? '已选择简历' : '尚未选择简历'}
            aria-live="polite"
            className="analysis-upload-selection"
            role="status"
          >
            {selectedFile ? (
              <>
                <FileText aria-hidden="true" size={18} />
                <span className="analysis-file-name">{selectedFile.name}</span>
                <span className="analysis-file-size">
                  {formatFileSize(selectedFile.size)}
                </span>
              </>
            ) : (
              <span className="analysis-upload-selection__empty">
                尚未选择文件
              </span>
            )}
          </div>
        </section>

        <section
          aria-labelledby="analysis-role-heading"
          className="analysis-form__section"
        >
          <div className="analysis-form__section-heading">
            <h2 id="analysis-role-heading">岗位信息</h2>
            <span aria-hidden="true" className="analysis-form__step">
              02
            </span>
          </div>

          <div className="analysis-field">
            <label htmlFor={TITLE_INPUT_ID}>岗位名称</label>
            <input
              {...jobTitleRegistration}
              aria-describedby={fieldDescription(
                TITLE_HELP_ID,
                TITLE_ERROR_ID,
                errors.jobTitle !== undefined,
              )}
              aria-invalid={errors.jobTitle ? true : undefined}
              autoComplete="off"
              disabled={submissionMutation.isPending}
              id={TITLE_INPUT_ID}
              type="text"
            />
            <p className="analysis-field-help" id={TITLE_HELP_ID}>
              最多 120 个字符
            </p>
            {errors.jobTitle ? (
              <p className="analysis-field-error" id={TITLE_ERROR_ID}>
                {errors.jobTitle.message}
              </p>
            ) : null}
          </div>

          <div className="analysis-field">
            <label htmlFor={CONTENT_INPUT_ID}>岗位 JD</label>
            <textarea
              {...jobContentRegistration}
              aria-describedby={fieldDescription(
                CONTENT_HELP_ID,
                CONTENT_ERROR_ID,
                errors.jobContent !== undefined,
              )}
              aria-invalid={errors.jobContent ? true : undefined}
              disabled={submissionMutation.isPending}
              id={CONTENT_INPUT_ID}
              rows={9}
            />
            <p className="analysis-field-help" id={CONTENT_HELP_ID}>
              最多 20,000 个字符
            </p>
            {errors.jobContent ? (
              <p className="analysis-field-error" id={CONTENT_ERROR_ID}>
                {errors.jobContent.message}
              </p>
            ) : null}
          </div>
        </section>

        <section
          aria-labelledby="analysis-confirmation-heading"
          className="analysis-confirmation"
        >
          <h2 id="analysis-confirmation-heading">提交确认</h2>
          <dl>
            <div>
              <dt>简历文件</dt>
              <dd className="analysis-file-name">
                {selectedFile?.name ?? '尚未选择'}
              </dd>
            </div>
            <div>
              <dt>岗位名称</dt>
              <dd>{confirmationTitle || '尚未填写'}</dd>
            </div>
          </dl>
        </section>

        <div className="analysis-form__actions">
          {submissionMutation.isError ? (
            <div className="analysis-submit-error" role="alert">
              <span>提交失败，请稍后重试。</span>
              {requestId ? <span>关联 ID：{requestId}</span> : null}
            </div>
          ) : (
            <span aria-hidden="true" className="analysis-form__action-spacer" />
          )}
          <Button
            className="analysis-form__submit"
            loading={submissionMutation.isPending}
            type="submit"
          >
            <Send aria-hidden="true" size={17} />
            <span>提交分析</span>
          </Button>
        </div>
      </form>
    </section>
  )
}
