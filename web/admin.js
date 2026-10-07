const loginPanel = document.querySelector('#login-panel');
const dashboard = document.querySelector('#dashboard');
const loginForm = document.querySelector('#login-form');
const loginStatus = document.querySelector('#login-status');
const productForm = document.querySelector('#product-form');
const productList = document.querySelector('#product-list');
const requestList = document.querySelector('#request-list');
const contentForm = document.querySelector('#content-form');
const productStatus = document.querySelector('#product-status');
const contentStatus = document.querySelector('#content-status');
const dashboardStatus = document.querySelector('#dashboard-status');
let csrfToken = '';
let productCache = [];

async function request(url, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
  if (csrfToken && options.method && options.method !== 'GET') headers['X-CSRF-Token'] = csrfToken;
  const response = await fetch(url, { ...options, headers, credentials: 'same-origin' });
  const result = await response.json();
  if (!response.ok) throw new Error(result.message || 'The request could not be completed.');
  return result;
}

function showDashboard(token) {
  csrfToken = token;
  loginPanel.hidden = true;
  dashboard.hidden = false;
  loadDashboard().catch((error) => { dashboardStatus.textContent = error.message; });
}

async function restoreSession() {
  try {
    const session = await request('/api/admin/session');
    showDashboard(session.csrf);
  } catch (error) {
    if (error.message !== 'Please sign in again.') loginStatus.textContent = error.message;
  }
}

loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = loginForm.querySelector('button[type="submit"]');
  button.disabled = true;
  loginStatus.textContent = 'Signing in...';
  try {
    const result = await request('/api/admin/login', {
      method: 'POST',
      body: new URLSearchParams(new FormData(loginForm))
    });
    loginForm.reset();
    loginStatus.textContent = '';
    showDashboard(result.csrf);
  } catch (error) {
    loginStatus.textContent = error.message;
  } finally {
    button.disabled = false;
  }
});

document.querySelector('#logout-button').addEventListener('click', async () => {
  try {
    await request('/api/admin/logout', { method: 'POST', body: new URLSearchParams() });
    csrfToken = '';
    dashboard.hidden = true;
    loginPanel.hidden = false;
    loginStatus.textContent = 'You are signed out.';
  } catch (error) {
    dashboardStatus.textContent = error.message;
  }
});

document.querySelectorAll('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((item) => item.classList.toggle('active', item === tab));
    document.querySelectorAll('.admin-section').forEach((section) => {
      section.hidden = section.id !== tab.dataset.section;
    });
    if (tab.dataset.section === 'requests-panel') loadRequests().catch(showDashboardError);
  });
});

async function loadDashboard() {
  const [products, content] = await Promise.all([request('/api/products'), request('/api/content')]);
  productCache = products;
  renderProducts();
  for (const [key, value] of Object.entries(content)) {
    const field = contentForm.elements.namedItem(key);
    if (field) field.value = value;
  }
}

function renderProducts() {
  productList.replaceChildren();
  if (productCache.length === 0) {
    productList.textContent = 'No products yet. Add your first look above.';
    return;
  }
  productCache.forEach((product) => {
    const card = document.createElement('article');
    card.className = 'list-card product-row';
    const image = document.createElement('div');
    window.renderGarmentArt(image, product);
    const details = document.createElement('div');
    const name = document.createElement('h3');
    name.textContent = product.name;
    const description = document.createElement('p');
    description.textContent = `${product.description} · ${product.colour.replace('-', ' ')} · $${product.price}`;
    details.append(name, description);
    const actions = document.createElement('div');
    actions.className = 'row-actions';
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'quiet-button';
    edit.textContent = 'Edit';
    edit.addEventListener('click', () => editProduct(product));
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'danger-button';
    remove.textContent = 'Remove';
    remove.addEventListener('click', () => deleteProduct(product));
    actions.append(edit, remove);
    card.append(image, details, actions);
    productList.append(card);
  });
}

function editProduct(product) {
  for (const field of productForm.elements) {
    if (field.name && Object.hasOwn(product, field.name)) field.value = product[field.name];
  }
  const styles = new Set(product.style.split(/\s+/));
  for (const option of document.querySelector('#product-style').options) option.selected = styles.has(option.value);
  document.querySelector('#cancel-edit').hidden = false;
  productForm.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function resetProductForm() {
  productForm.reset();
  for (const option of document.querySelector('#product-style').options) option.selected = false;
  document.querySelector('#cancel-edit').hidden = true;
}

document.querySelector('#cancel-edit').addEventListener('click', resetProductForm);

productForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = new URLSearchParams(new FormData(productForm));
  data.set('style', [...document.querySelector('#product-style').selectedOptions].map((option) => option.value).join(' '));
  try {
    productStatus.textContent = 'Saving product...';
    await request('/api/admin/products', { method: 'POST', body: data });
    resetProductForm();
    productStatus.textContent = 'Product saved.';
    await loadDashboard();
  } catch (error) {
    productStatus.textContent = error.message;
  }
});

async function deleteProduct(product) {
  if (!confirm(`Remove “${product.name}” from the shop?`)) return;
  try {
    const result = await request(`/api/admin/products?id=${encodeURIComponent(product.id)}`, { method: 'DELETE' });
    dashboardStatus.textContent = result.message;
    await loadDashboard();
  } catch (error) {
    showDashboardError(error);
  }
}

contentForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    contentStatus.textContent = 'Saving homepage text...';
    const result = await request('/api/admin/content', {
      method: 'POST',
      body: new URLSearchParams(new FormData(contentForm))
    });
    contentStatus.textContent = result.message;
  } catch (error) {
    contentStatus.textContent = error.message;
  }
});

document.querySelector('#refresh-requests').addEventListener('click', () => {
  loadRequests().catch(showDashboardError);
});

async function loadRequests() {
  const requests = await request('/api/admin/requests');
  document.querySelector('#request-count').textContent = requests.filter((item) => item.status === 'new').length || '';
  requestList.replaceChildren();
  if (!requests.length) {
    requestList.textContent = 'No styling requests yet.';
    return;
  }
  requests.reverse().forEach((item) => {
    const card = document.createElement('article');
    card.className = 'list-card request-card';
    const info = document.createElement('div');
    const heading = document.createElement('h3');
    heading.textContent = `${item.customer} · ${item.status}`;
    const email = document.createElement('a');
    email.href = `mailto:${encodeURIComponent(item.email)}`;
    email.textContent = item.email;
    const message = document.createElement('p');
    message.textContent = `${item.items} — ${item.finish}`;
    const date = document.createElement('small');
    date.textContent = new Date(item.createdAt).toLocaleString();
    info.append(heading, email, message, date);
    const actions = document.createElement('div');
    actions.className = 'row-actions';
    const state = document.createElement('select');
    state.setAttribute('aria-label', `Status for ${item.customer}'s request`);
    for (const value of ['new', 'contacted', 'complete']) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value[0].toUpperCase() + value.slice(1);
      option.selected = value === item.status;
      state.append(option);
    }
    state.addEventListener('change', async () => {
      try {
        const data = new URLSearchParams({ id: item.id, status: state.value });
        await request('/api/admin/requests/status', { method: 'POST', body: data });
        await loadRequests();
      } catch (error) {
        showDashboardError(error);
      }
    });
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'danger-button';
    remove.textContent = 'Delete';
    remove.addEventListener('click', async () => {
      if (!confirm('Permanently delete this styling request?')) return;
      try {
        await request('/api/admin/requests/delete', {
          method: 'POST',
          body: new URLSearchParams({ id: item.id })
        });
        await loadRequests();
      } catch (error) {
        showDashboardError(error);
      }
    });
    actions.append(state, remove);
    card.append(info, actions);
    requestList.append(card);
  });
}

function showDashboardError(error) {
  dashboardStatus.textContent = error.message;
}

restoreSession();
