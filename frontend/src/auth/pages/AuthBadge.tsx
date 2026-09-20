export function AuthBadge() {
  return (
    <div className="auth-badge" aria-hidden="true">
      <svg className="auth-badge-cog" viewBox="0 0 100 100" fill="none">
        <defs>
          <linearGradient id="auth-brass" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#e8c877" />
            <stop offset="35%" stopColor="#c9a24a" />
            <stop offset="65%" stopColor="#9a7530" />
            <stop offset="100%" stopColor="#6e5423" />
          </linearGradient>
          <radialGradient id="auth-hub-shade" cx="0.5" cy="0.35" r="0.75">
            <stop offset="0%" stopColor="#e8c877" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#000000" stopOpacity="0.35" />
          </radialGradient>
        </defs>
        <path
          d="M86.83 42.92L96.77 45.31A47 47 0 0 1 96.77 54.69L86.83 57.08A37.5 37.5 0 0 1 85.43 62.28L92.85 69.32A47 47 0 0 1 88.15 77.45L78.35 74.55A37.5 37.5 0 0 1 74.55 78.35L77.45 88.15A47 47 0 0 1 69.32 92.85L62.28 85.43A37.5 37.5 0 0 1 57.08 86.83L54.69 96.77A47 47 0 0 1 45.31 96.77L42.92 86.83A37.5 37.5 0 0 1 37.72 85.43L30.68 92.85A47 47 0 0 1 22.55 88.15L25.45 78.35A37.5 37.5 0 0 1 21.65 74.55L11.85 77.45A47 47 0 0 1 7.15 69.32L14.57 62.28A37.5 37.5 0 0 1 13.17 57.08L3.23 54.69A47 47 0 0 1 3.23 45.31L13.17 42.92A37.5 37.5 0 0 1 14.57 37.72L7.15 30.68A47 47 0 0 1 11.85 22.55L21.65 25.45A37.5 37.5 0 0 1 25.45 21.65L22.55 11.85A47 47 0 0 1 30.68 7.15L37.72 14.57A37.5 37.5 0 0 1 42.92 13.17L45.31 3.23A47 47 0 0 1 54.69 3.23L57.08 13.17A37.5 37.5 0 0 1 62.28 14.57L69.32 7.15A47 47 0 0 1 77.45 11.85L74.55 21.65A37.5 37.5 0 0 1 78.35 25.45L88.15 22.55A47 47 0 0 1 92.85 30.68L85.43 37.72A37.5 37.5 0 0 1 86.83 42.92Z"
          fill="url(#auth-brass)"
          stroke="#5c4419"
          strokeWidth="1.4"
        />
        <circle
          cx="50"
          cy="50"
          r="35.5"
          fill="none"
          stroke="#5c4419"
          strokeWidth="1.4"
          opacity="0.9"
        />
        <circle
          cx="50"
          cy="50"
          r="34"
          fill="none"
          stroke="#e8c877"
          strokeWidth="0.8"
          opacity="0.45"
        />
        <circle
          cx="50"
          cy="50"
          r="29"
          fill="url(#auth-hub-shade)"
          stroke="#5c4419"
          strokeWidth="1.4"
        />
        <g fill="url(#auth-brass)" stroke="#5c4419" strokeWidth="0.8">
          <circle cx="50" cy="26.5" r="2.6" />
          <circle cx="70.9" cy="38.6" r="2.6" />
          <circle cx="70.9" cy="61.4" r="2.6" />
          <circle cx="50" cy="73.5" r="2.6" />
          <circle cx="29.1" cy="61.4" r="2.6" />
          <circle cx="29.1" cy="38.6" r="2.6" />
        </g>
      </svg>
      <svg className="auth-badge-icon" viewBox="0 0 24 24">
        <path
          d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"
          fill="url(#auth-brass)"
          stroke="#5c4419"
          strokeWidth="1.2"
          strokeLinejoin="round"
        />
        <path
          d="M7.3 9.2c1.2-1.5 2.9-2.4 4.9-2.4 1.9 0 3.7.9 4.9 2.4"
          fill="none"
          stroke="#fff7e0"
          strokeWidth="1.4"
          strokeLinecap="round"
          opacity="0.4"
        />
        <circle cx="8.4" cy="12.6" r="1.2" fill="#4a3714" />
        <circle cx="12" cy="12.6" r="1.2" fill="#4a3714" />
        <circle cx="15.6" cy="12.6" r="1.2" fill="#4a3714" />
      </svg>
    </div>
  )
}
