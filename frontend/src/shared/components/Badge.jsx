import { label } from '../format.js';
export default function Badge({ value }) {
  return (
    <span className={`badge ${String(value).toLowerCase().replaceAll(' ', '-')}`}>
      <i />
      {label(value)}
    </span>
  );
}
