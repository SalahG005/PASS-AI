export interface PasswordRule {
  label: string;
  matched: boolean;
}

export interface PasswordStrength {
  percentage: number;
  score: number;
  label: string;
  rules: PasswordRule[];
}

export function getPasswordStrength(password: string): PasswordStrength {
  const rules: PasswordRule[] = [
    { label: 'At least 8 characters', matched: password.length >= 8 },
    { label: 'One uppercase letter', matched: /[A-Z]/.test(password) },
    { label: 'One lowercase letter', matched: /[a-z]/.test(password) },
    { label: 'One number', matched: /\d/.test(password) },
    { label: 'One special character', matched: /[^A-Za-z0-9]/.test(password) }
  ];

  const score = rules.filter((rule) => rule.matched).length;
  const percentage = Math.round((score / rules.length) * 100);

  let label = 'Weak';
  if (percentage >= 80) {
    label = 'Strong';
  } else if (percentage >= 40) {
    label = 'Fair';
  }

  return { percentage, score, label, rules };
}
