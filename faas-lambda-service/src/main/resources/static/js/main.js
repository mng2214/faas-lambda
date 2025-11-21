function setupLayout() {
    const sidebar = document.getElementById('sidebar');
    const header = document.getElementById('header');
    const mainContent = document.getElementById('mainContent');
    const footer = document.getElementById('footer');
    const toggleSidebar = document.getElementById('toggleSidebar');
    const body = document.body;

    if (!sidebar || !header || !mainContent || !footer || !toggleSidebar || !body) return;

    const pageKey = body.dataset.page;

    // Заголовок страницы
    const pageTitleEl = document.getElementById('pageTitle');
    const titles = {
        dashboard: 'Dispatcher Dashboard',
        inbox: 'Inbox — Messages',
        numbers: 'Numbers — Virtual Lines',
        tracking: 'Tracking Center',
        drivers: 'Drivers',
        dispatchers: 'Dispatchers',
        logs: 'Activity Logs',
        integrations: 'Integrations',
        settings: 'Settings'
    };
    if (pageTitleEl && pageKey && titles[pageKey]) {
        pageTitleEl.textContent = titles[pageKey];
    }

    // Активный пункт меню
    if (pageKey) {
        const activeItem = sidebar.querySelector('.menu-item[data-page="' + pageKey + '"]');
        if (activeItem) {
            activeItem.classList.add('active');
        }
    }

    // Клик по бургеру
    toggleSidebar.addEventListener('click', function () {
        const isMobile = window.innerWidth <= 1024;

        if (isMobile) {
            sidebar.classList.toggle('mobile-open');
        } else {
            sidebar.classList.toggle('collapsed');
            header.classList.toggle('sidebar-collapsed');
            mainContent.classList.toggle('sidebar-collapsed');
            footer.classList.toggle('sidebar-collapsed');
        }
    });

    // Мобильное начальное состояние
    if (window.innerWidth <= 1024) {
        sidebar.classList.add('collapsed');
        header.classList.add('sidebar-collapsed');
        mainContent.classList.add('sidebar-collapsed');
        footer.classList.add('sidebar-collapsed');
    }

    // Hover для карточек
    const cards = document.querySelectorAll('.card');
    cards.forEach(card => {
        card.addEventListener('mouseenter', function () {
            this.style.transform = 'translateY(-8px)';
        });
        card.addEventListener('mouseleave', function () {
            this.style.transform = 'translateY(0)';
        });
    });

    // Анимация появления
    const fadeElements = document.querySelectorAll('.fade-in');
    const observer = new IntersectionObserver(entries => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('visible');
            }
        });
    }, { threshold: 0.1 });

    fadeElements.forEach(el => observer.observe(el));

    setTimeout(() => {
        fadeElements.forEach(el => el.classList.add('visible'));
    }, 100);
}

document.addEventListener('DOMContentLoaded', setupLayout);
