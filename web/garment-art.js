(() => {
  const colours = {
    black: ['#292421', '#c4a45d', '#f4eee4'],
    brown: ['#70452f', '#d2aa68', '#f0e1c8'],
    'dark-yellow': ['#a77b19', '#251f1d', '#f6e7b7'],
    'dark-pink': ['#922e50', '#e2b7c2', '#f4e8dd'],
    white: ['#f0e8d9', '#70452f', '#a47f42']
  };
  let nextId = 0;

  window.renderGarmentArt = (container, product) => {
    const [main, accent, light] = colours[product.colour] || colours.brown;
    const styles = product.style.split(/\s+/);
    const print = styles.includes('print') || styles.includes('mixed');
    const embroidered = styles.includes('embroidered') || styles.includes('backless')
      || styles.includes('elegance') || styles.includes('occasion');
    const id = `fabric-${nextId++}`;
    const textile = print || styles.includes('boubou')
      ? 'texture-african.jpg'
      : 'texture-kaftan.jpg';
    const garment = styles.includes('wrap')
      ? 'M164 130 132 143 91 201l31 19 24-31-7 62-21 160q82 28 164 0l-21-160-7-62 24 31 31-19-41-58-32-13-36 27z'
      : styles.includes('boubou')
        ? 'M164 130 121 144 73 209l41 25 39-43-19 219q66 25 132 0l-19-219 39 43 41-25-48-65-43-14-36 27z'
        : styles.includes('flare')
          ? 'M164 130 132 143 76 204l41 27 39-40-8 61-24 158q76 28 152 0l-24-158-8-61 39 40 41-27-56-61-32-13-36 27z'
          : styles.includes('maxi')
            ? 'M164 130 132 143 92 198l30 22 23-30-8 61-21 161q84 32 168 0l-21-161-8-61 23 30 30-22-40-55-32-13-36 27z'
            : 'M164 130 132 143 94 198l29 22 22-30-7 61-22 161q84 31 168 0l-22-161-7-61 22 30 29-22-38-55-32-13-36 27z';

    const safeName = product.name.replace(/[<>&"]/g, '');
    container.classList.add('garment-art');
    container.innerHTML = `<svg viewBox="0 0 400 470" role="img" aria-label="Photographic Dera fabric displayed on a faceless mannequin, ${safeName}" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="${id}-bg" x2="0" y2="1"><stop stop-color="#faf7f1"/><stop offset="1" stop-color="#e6dbcd"/></linearGradient>
        <linearGradient id="${id}-mannequin" x1="0" y1="0" x2="1" y2=".7">
          <stop stop-color="#fffaf0"/><stop offset=".42" stop-color="#d9cdbc"/><stop offset=".72" stop-color="#f0e7da"/><stop offset="1" stop-color="#b9a995"/>
        </linearGradient>
        <linearGradient id="${id}-cloth" x1="0" y1="0" x2="1" y2=".25">
          <stop stop-color="#171412" stop-opacity=".36"/><stop offset=".2" stop-color="#fff" stop-opacity=".24"/>
          <stop offset=".52" stop-color="#fff" stop-opacity="0"/><stop offset=".82" stop-color="#171412" stop-opacity=".13"/>
          <stop offset="1" stop-color="#171412" stop-opacity=".4"/>
        </linearGradient>
        <linearGradient id="${id}-fold" x2="1">
          <stop stop-color="#171412" stop-opacity=".16"/><stop offset=".12" stop-color="#fff" stop-opacity=".13"/>
          <stop offset=".3" stop-color="#171412" stop-opacity=".08"/><stop offset=".47" stop-color="#fff" stop-opacity=".11"/>
          <stop offset=".67" stop-color="#171412" stop-opacity=".12"/><stop offset=".84" stop-color="#fff" stop-opacity=".1"/>
          <stop offset="1" stop-color="#171412" stop-opacity=".18"/>
        </linearGradient>
        <pattern id="${id}-photo-fabric" width="250" height="320" patternUnits="userSpaceOnUse">
          <image href="/images/${textile}" width="250" height="320" preserveAspectRatio="xMidYMid slice"/>
        </pattern>
        <clipPath id="${id}-dress"><path d="${garment}"/></clipPath>
      </defs>
      <rect width="400" height="470" fill="url(#${id}-bg)"/>
      <ellipse cx="200" cy="426" rx="116" ry="15" fill="#533b2c" opacity=".16"/>
      <path d="M190 402v27q-39 4-53 12h126q-14-8-53-12v-27" fill="#9d8d7a"/>
      <path d="M94 167q-14 12-20 40l-13 54q-3 14 8 17 10 2 14-12l19-48 19-26m174-25q14 12 20 40l13 54q3 14-8 17-10 2-14-12l-19-48-19-26" fill="none" stroke="url(#${id}-mannequin)" stroke-width="17" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="M179 85q-18-17-11-38 8-22 32-22t32 22q7 21-11 38l-4 12h-34z" fill="url(#${id}-mannequin)" stroke="#b5a691" stroke-width="1.5"/>
      <path d="M181 65q18 9 38 0" fill="none" stroke="#a79784" stroke-opacity=".38" stroke-width="1.4"/>
      <path d="M184 86v42h32V86" fill="url(#${id}-mannequin)" stroke="#b5a691" stroke-width="1.5"/>
      <path d="M178 96q22 12 44 0" fill="none" stroke="#a79784" stroke-opacity=".5" stroke-width="1.5"/>
      <path d="${garment}" fill="${main}" stroke="${accent}" stroke-width="2.5" stroke-linejoin="round"/>
      <g clip-path="url(#${id}-dress)">
        <rect x="65" y="120" width="270" height="325" fill="url(#${id}-photo-fabric)"/>
        <rect x="65" y="120" width="270" height="325" fill="${main}" opacity=".08"/>
        <rect x="65" y="120" width="270" height="325" fill="url(#${id}-fold)"/>
        <path d="M196 154q-5 100 4 259M151 238q49 20 98 0M127 337q72 25 146 0" fill="none" stroke="#fff7e8" stroke-opacity=".35" stroke-width="2"/>
        <path d="M184 143q16 19 32 0M169 155q31 29 62 0" fill="none" stroke="${accent}" stroke-width="2"/>
        ${embroidered ? `<path d="M176 157q24 25 48 0M173 165q27 27 54 0M116 361q84 35 168 0" fill="none" stroke="${light}" stroke-width="2.2" stroke-dasharray="1 5" stroke-linecap="round"/>
          <path d="m190 183 10 11 10-11-10-11zm0 27 10 11 10-11-10-11z" fill="${accent}" stroke="${light}" stroke-width="1.2"/>
          <g fill="${light}"><circle cx="200" cy="235" r="2.5"/><circle cx="200" cy="251" r="2.5"/><circle cx="200" cy="267" r="2.5"/></g>` : ''}
        ${styles.includes('backless') ? `<path d="M181 133q19 31 38 0" fill="none" stroke="${light}" stroke-width="4"/>` : ''}
      </g>
      <path d="${garment}" fill="url(#${id}-cloth)" stroke="${accent}" stroke-width="2.5" stroke-linejoin="round"/>
      <path d="M190 412q10 5 20 0" fill="none" stroke="${light}" stroke-width="2" opacity=".8"/>
      <text x="200" y="457" text-anchor="middle" fill="#533b2c" font-family="Arial,sans-serif" font-size="9" letter-spacing="2">PEACECOLLECTION · DERA</text>
    </svg>`;
  };
})();
