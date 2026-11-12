export default function ErrorBox({ error, onRetry }) {
  return (
    error && (
      <div className="notice error" role="alert">
        <div>
          <strong>
            {error.code === 'CALIBRATION_CONFLICT'
              ? 'Choose a narrower interval'
              : 'Request needs attention'}
          </strong>
          <p>{error.message}</p>
          {error.details?.map((d) => (
            <p key={d.field}>
              {d.field}: {d.reason}
            </p>
          ))}
        </div>
        {onRetry && <button onClick={onRetry}>Retry</button>}
      </div>
    )
  );
}
