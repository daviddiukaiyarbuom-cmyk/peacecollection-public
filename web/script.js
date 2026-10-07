const menu = document.querySelector('.menu-toggle');
const nav = document.querySelector('.topbar nav');
const count = document.querySelector('#bag-count');
const items = document.querySelector('#items');
const form = document.querySelector('#order-form');
const status = document.querySelector('#order-status');
const styleFilters = document.querySelectorAll('.filter');
const colourFilter = document.querySelector('#colour-filter');
const productGrid = document.querySelector('#products');
const resultsStatus = document.querySelector('#results-status');
const emptyResults = document.querySelector('#empty-results');
let products = [];
let totalItems = 0;
let selectedStyle = 'all';

menu.addEventListener('click', () => {
  const isOpen = nav.classList.toggle('open');
  menu.setAttribute('aria-expanded', String(isOpen));
});

nav.querySelectorAll('a').forEach((link) => {
  link.addEventListener('click', () => {
    nav.classList.remove('open');
    menu.setAttribute('aria-expanded', 'false');
  });
});

function renderProducts() {
  productGrid.replaceChildren();
  products.forEach((product, index) => {
    const card = document.createElement('article');
    card.className = 'product-card';
    card.dataset.style = product.style;
    card.dataset.colour = product.colour;

    const imageContainer = document.createElement('div');
    imageContainer.className = 'product-image';
    window.renderGarmentArt(imageContainer, product);
    const number = document.createElement('span');
    number.className = 'look-number';
    number.textContent = `${String(index + 1).padStart(2, '0')} / BD`;
    const tag = document.createElement('span');
    tag.className = 'look-tag';
    tag.textContent = product.style.split(' ').map((style) => style === 'print' ? 'AFRICAN PRINT' : style.toUpperCase()).join(' · ');
    imageContainer.append(number, tag);

    const details = document.createElement('div');
    details.className = 'product-details';
    const copy = document.createElement('div');
    const name = document.createElement('h3');
    name.textContent = product.name;
    const description = document.createElement('p');
    description.textContent = product.description;
    copy.append(name, description);
    const price = document.createElement('b');
    price.textContent = `$${product.price}`;
    details.append(copy, price);

    const add = document.createElement('button');
    add.className = 'add';
    add.type = 'button';
    add.dataset.product = `${product.name} — $${product.price}`;
    add.append(document.createTextNode('Add to bag '));
    const plus = document.createElement('span');
    plus.textContent = '+';
    add.append(plus);
    card.append(imageContainer, details, add);
    productGrid.append(card);
  });
  filterProducts();
}

function filterProducts() {
  const selectedColour = colourFilter.value;
  let visibleProducts = 0;
  const cards = productGrid.querySelectorAll('.product-card');
  cards.forEach((product) => {
    const matchesStyle = selectedStyle === 'all' || product.dataset.style.split(/\s+/).includes(selectedStyle);
    const matchesColour = selectedColour === 'all' || product.dataset.colour === selectedColour;
    const isVisible = matchesStyle && matchesColour;
    product.hidden = !isVisible;
    if (isVisible) visibleProducts += 1;
  });
  resultsStatus.textContent = `${visibleProducts} ${visibleProducts === 1 ? 'look' : 'looks'} to explore`;
  emptyResults.hidden = visibleProducts > 0;
}

styleFilters.forEach((button) => {
  button.addEventListener('click', () => {
    selectedStyle = button.dataset.style;
    styleFilters.forEach((filter) => {
      const isActive = filter === button;
      filter.classList.toggle('active', isActive);
      filter.setAttribute('aria-pressed', String(isActive));
    });
    filterProducts();
  });
});
colourFilter.addEventListener('change', filterProducts);

productGrid.addEventListener('click', (event) => {
  const button = event.target.closest('.add');
  if (!button) return;
  const product = button.dataset.product;
  const selected = items.value.split('\n').filter(Boolean);
  selected.push(product);
  items.value = selected.join('\n');
  totalItems += 1;
  count.textContent = String(totalItems);
  status.textContent = `${product} added. Add your details to request styling help.`;
  document.querySelector('#custom').scrollIntoView({ behavior: 'smooth' });
});

async function loadStorefront() {
  try {
    const [productResponse, contentResponse] = await Promise.all([
      fetch('/api/products'),
      fetch('/api/content')
    ]);
    if (!productResponse.ok || !contentResponse.ok) throw new Error('Could not load the boutique catalogue.');
    products = await productResponse.json();
    const content = await contentResponse.json();
    document.querySelector('#announcement-copy').textContent = content.announcement;
    document.querySelector('#hero-description').textContent = content.heroDescription;
    document.querySelector('#story-first').textContent = content.storyFirst;
    document.querySelector('#story-second').textContent = content.storySecond;
    window.renderGarmentArt(document.querySelector('#hero-garment'), {
      name: 'Decorated Backless African Print Dera',
      colour: 'brown',
      style: 'print backless embroidered'
    });
    renderProducts();
  } catch (error) {
    resultsStatus.textContent = error.message;
    emptyResults.hidden = false;
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const submitButton = form.querySelector('button[type="submit"]');
  submitButton.disabled = true;
  status.textContent = 'Sending your styling request...';
  try {
    const response = await fetch('/api/order', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: new URLSearchParams(new FormData(form))
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Could not send your request.');
    status.textContent = result.message;
    form.reset();
    totalItems = 0;
    count.textContent = '0';
  } catch (error) {
    status.textContent = error.message === 'Failed to fetch'
      ? 'Could not reach the boutique server. Please try again.'
      : error.message;
  } finally {
    submitButton.disabled = false;
  }
});

loadStorefront();
